package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class OrderSigningService {

    @Value("${polymarket.wallet.private-key:}")
    private String privateKey;

    @Value("${polymarket.wallet.proxy-address:}")
    private String proxyAddress;

    private static final String DOMAIN_NAME = "Polymarket CTF Exchange";
    private static final String DOMAIN_VERSION = "1";
    private static final long CHAIN_ID = 137;
    private static final String VERIFYING_CONTRACT = "0x4bFB41d5B3570DeFd03C39A9A4D8de6bd8b8982E";

    public String signOrder(String tokenId, String makerAmount, String takerAmount, int side, long expiration) {
        if (privateKey == null || privateKey.isEmpty()) {
            throw new RuntimeException("Private key not configured for live trading.");
        }

        try {
            Credentials credentials = Credentials.create(privateKey);
            
            Map<String, Object> types = new HashMap<>();
            types.put("EIP712Domain", List.of(
                Map.of("name", "name", "type", "string"),
                Map.of("name", "version", "type", "string"),
                Map.of("name", "chainId", "type", "uint256"),
                Map.of("name", "verifyingContract", "type", "address")
            ));
            types.put("Order", List.of(
                Map.of("name", "salt", "type", "uint256"),
                Map.of("name", "maker", "type", "address"),
                Map.of("name", "signer", "type", "address"),
                Map.of("name", "taker", "type", "address"),
                Map.of("name", "tokenId", "type", "uint256"),
                Map.of("name", "makerAmount", "type", "uint256"),
                Map.of("name", "takerAmount", "type", "uint256"),
                Map.of("name", "expiration", "type", "uint256"),
                Map.of("name", "nonce", "type", "uint256"),
                Map.of("name", "feeRateBps", "type", "uint256"),
                Map.of("name", "side", "type", "uint8"),
                Map.of("name", "signatureType", "type", "uint8")
            ));

            Map<String, Object> domain = new HashMap<>();
            domain.put("name", DOMAIN_NAME);
            domain.put("version", DOMAIN_VERSION);
            domain.put("chainId", CHAIN_ID);
            domain.put("verifyingContract", VERIFYING_CONTRACT);

            Map<String, Object> message = new HashMap<>();
            message.put("salt", String.valueOf(System.currentTimeMillis()));
            message.put("maker", proxyAddress);
            message.put("signer", credentials.getAddress());
            message.put("taker", "0x0000000000000000000000000000000000000000");
            message.put("tokenId", tokenId);
            message.put("makerAmount", makerAmount);
            message.put("takerAmount", takerAmount);
            message.put("expiration", String.valueOf(expiration));
            message.put("nonce", "0");
            message.put("feeRateBps", "0");
            message.put("side", side);
            message.put("signatureType", 2); // GNOSIS_SAFE

            Map<String, Object> data = new HashMap<>();
            data.put("types", types);
            data.put("primaryType", "Order");
            data.put("domain", domain);
            data.put("message", message);

            String json = new ObjectMapper().writeValueAsString(data);
            StructuredDataEncoder dataEncoder = new StructuredDataEncoder(json);

            byte[] hash = dataEncoder.hashStructuredData();
            Sign.SignatureData signatureData = Sign.signMessage(hash, credentials.getEcKeyPair(), false);
            
            byte[] signature = new byte[65];
            System.arraycopy(signatureData.getR(), 0, signature, 0, 32);
            System.arraycopy(signatureData.getS(), 0, signature, 32, 32);
            signature[64] = signatureData.getV()[0];
            
            return Numeric.toHexString(signature);
        } catch (Exception e) {
            log.error("Failed to sign order: {}", e.getMessage());
            throw new RuntimeException("Order signing failed", e);
        }
    }
}
