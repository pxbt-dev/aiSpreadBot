package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import weka.classifiers.Classifier;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.functions.SMOreg;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.SerializationHelper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class WekaAnalysisService {

    private final Map<String, Classifier[]> ensembles = new ConcurrentHashMap<>();
    private final Map<String, Instances> headers = new ConcurrentHashMap<>();
    private final Map<String, Instances> datasets = new ConcurrentHashMap<>(); // Actual training data
    private static final String MODEL_DIR = "models/";

    /** Features waiting for their outcome — keyed by tokenId */
    private final Map<String, double[]> pendingFeatures = new ConcurrentHashMap<>();
    /** How many real samples have been added since the last retrain */
    private final AtomicInteger samplesSinceRetrain = new AtomicInteger(0);
    private static final int RETRAIN_INTERVAL = 10;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(MODEL_DIR);
            if (!dir.exists()) dir.mkdirs();
            loadEnsembles();
        } catch (Exception e) {
            log.error("❌ Failed to initialize Weka ensembles: {}", e.getMessage());
        }
    }

    /**
     * Gets consensus score from 3 models (LR, SMOreg, Random Forest)
     */
    public double getConsensusScore(String marketType, double[] features) {
        Classifier[] ensemble = ensembles.get(marketType);
        Instances header = headers.get(marketType);

        if (ensemble == null || ensemble.length < 3 || header == null) {
            log.warn("⚠️ Ensemble not ready for {}. Training now...", marketType);
            trainInitialEnsemble(marketType);
            return 0.5;
        }

        try {
            double[] instanceValues = new double[features.length + 1];
            System.arraycopy(features, 0, instanceValues, 0, features.length);
            instanceValues[features.length] = weka.core.Utils.missingValue();
            DenseInstance instance = new DenseInstance(1.0, instanceValues);
            instance.setDataset(header);

            double scoreLR = ensemble[0].classifyInstance(instance);
            double scoreSMO = ensemble[1].classifyInstance(instance);
            double scoreRF = ensemble[2].classifyInstance(instance);

            // Normalized average
            double weightedScore = (scoreLR + scoreSMO + scoreRF) / 3.0;
            log.debug("🧠 Consensus [{}]: LR={}, SMO={}, RF={} -> Final={}", 
                marketType, String.format("%.2f", scoreLR), String.format("%.2f", scoreSMO), 
                String.format("%.2f", scoreRF), String.format("%.2f", weightedScore));
            
            return Math.max(0.0, Math.min(1.0, weightedScore));
        } catch (Exception e) {
            log.error("❌ Consensus failed for {}: {}", marketType, e.getMessage());
            return 0.5;
        }
    }

    public void trainInitialEnsemble(String marketType) {
        log.info("🤖 Training Ensemble (LR+SMOreg+RF) for {}...", marketType);
        
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("forecastProb"));
        attributes.add(new Attribute("tempDelta"));
        attributes.add(new Attribute("humidityDelta"));
        attributes.add(new Attribute("volatility"));
        attributes.add(new Attribute("actualOutcome"));

        Instances dataset = new Instances(marketType + "Reliability", attributes, 20);
        dataset.setClassIndex(dataset.numAttributes() - 1);

        // Core Training Logic (Default Samples)
        addSample(marketType, 0.9, 0.1, 0.1, 0.2, 0.98); 
        addSample(marketType, 0.7, 0.2, 0.2, 0.3, 0.75);
        addSample(marketType, 0.5, 0.5, 0.5, 0.6, 0.40);
        addSample(marketType, 0.9, 0.9, 0.9, 0.9, 0.10);

        buildAndSave(marketType);
    }

    public void buildAndSave(String marketType) {
        Instances dataset = datasets.get(marketType);
        if (dataset == null || dataset.numInstances() < 4) return;

        try {
            LinearRegression lr = new LinearRegression();
            lr.buildClassifier(dataset);

            SMOreg smo = new SMOreg();
            smo.buildClassifier(dataset);

            RandomForest rf = new RandomForest();
            rf.setNumIterations(50);
            rf.buildClassifier(dataset);

            ensembles.put(marketType, new Classifier[]{lr, smo, rf});
            headers.put(marketType, new Instances(dataset, 0));
            
            saveEnsemble(marketType);
            log.info("✅ Ensemble brain (re)built and saved for {} [Size: {}]", marketType, dataset.numInstances());
        } catch (Exception e) {
            log.error("❌ Failed to build ensemble: {}", e.getMessage());
        }
    }

    /**
     * Number of real (non-seed) training samples accumulated for a market type.
     * The ensemble is seeded with 4 synthetic samples; anything beyond that is real feedback.
     * Used to determine whether the CV gate should be enforced.
     */
    private static final int SEED_SAMPLE_COUNT = 4;

    public int getRealSampleCount(String marketType) {
        Instances ds = datasets.get(marketType);
        return ds == null ? 0 : Math.max(0, ds.numInstances() - SEED_SAMPLE_COUNT);
    }

    /**
     * Store entry features for a token so they can be paired with the outcome on close.
     * Call this immediately after a BUY is executed.
     */
    public void recordEntry(String tokenId, double[] features) {
        pendingFeatures.put(tokenId, features.clone());
        log.debug("📌 WEKA entry recorded for {}: features={}", tokenId, features);
    }

    /**
     * Pair the stored entry features with the observed outcome and add a training sample.
     * outcome: 1.0 = profitable trade, 0.0 = losing trade.
     * Triggers a full retrain every RETRAIN_INTERVAL real samples.
     * Call this immediately before a SELL (gap-reversal exit).
     */
    public void recordTradeOutcome(String tokenId, double outcome) {
        double[] features = pendingFeatures.remove(tokenId);
        if (features == null) {
            log.warn("⚠️ WEKA outcome for {} has no matching entry — skipping", tokenId);
            return;
        }
        addSample("Weather", features[0], features[1], features[2], features[3], outcome);
        int count = samplesSinceRetrain.incrementAndGet();
        log.info("🧠 WEKA feedback: tokenId={} outcome={} samples_since_retrain={}", tokenId, outcome, count);
        if (count >= RETRAIN_INTERVAL) {
            samplesSinceRetrain.set(0);
            log.info("🔄 WEKA retrain triggered after {} real samples", RETRAIN_INTERVAL);
            buildAndSave("Weather");
        }
    }

    public void addSample(String marketType, double f, double t, double h, double v, double outcome) {
        Instances dataset = datasets.computeIfAbsent(marketType, k -> {
            ArrayList<Attribute> attributes = new ArrayList<>();
            attributes.add(new Attribute("forecastProb"));
            attributes.add(new Attribute("tempDelta"));
            attributes.add(new Attribute("humidityDelta"));
            attributes.add(new Attribute("volatility"));
            attributes.add(new Attribute("actualOutcome"));
            Instances ins = new Instances(marketType + "Reliability", attributes, 100);
            ins.setClassIndex(ins.numAttributes() - 1);
            return ins;
        });
        dataset.add(new DenseInstance(1.0, new double[]{f, t, h, v, outcome}));
        
        // Save dataset after adding sample
        try {
            saveEnsemble(marketType);
        } catch (Exception e) {
            log.error("❌ Failed to save dataset: {}", e.getMessage());
        }
    }

    private void saveEnsemble(String key) throws Exception {
        SerializationHelper.write(MODEL_DIR + key + "_ensemble.bin", ensembles.get(key));
        SerializationHelper.write(MODEL_DIR + key + "_header.bin", headers.get(key));
        // Save actual data as ARFF
        if (datasets.containsKey(key)) {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(MODEL_DIR + key + "_data.arff"), 
                datasets.get(key).toString()
            );
        }
    }

    private void loadEnsembles() {
        File dir = new File(MODEL_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith("_ensemble.bin"));
        if (files == null) return;

        for (File file : files) {
            String key = file.getName().replace("_ensemble.bin", "");
            try {
                // Load Ensembles
                Classifier[] ensemble = (Classifier[]) SerializationHelper.read(file.getAbsolutePath());
                Instances header = (Instances) SerializationHelper.read(MODEL_DIR + key + "_header.bin");
                ensembles.put(key, ensemble);
                headers.put(key, header);

                // Load Data ARFF if available
                File arffFile = new File(MODEL_DIR + key + "_data.arff");
                if (arffFile.exists()) {
                    Instances dataset = new Instances(new java.io.BufferedReader(new java.io.FileReader(arffFile)));
                    dataset.setClassIndex(dataset.numAttributes() - 1);
                    datasets.put(key, dataset);
                    log.info("📂 Previous training data restored for {}: {} samples", key, dataset.numInstances());
                }

                log.info("📂 Ensemble brain restored: {}", key);
            } catch (Exception e) {
                log.warn("⚠️ Could not restore ensemble {}: {}", key, e.getMessage());
            }
        }
    }
}
