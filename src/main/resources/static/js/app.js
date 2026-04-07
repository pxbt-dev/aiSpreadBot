const confidenceHistory = [];
let lastStatsTime = 0;

document.addEventListener('DOMContentLoaded', () => {
    initSvgConnections();
    connectWebSocket();
    initStaleChecker();
});

const edges = [
    { from: 'node-scanner', to: 'node-post-bid' },
    { from: 'node-weather', to: 'node-post-ask' },
    { from: 'node-weka', to: 'node-bot' },
    { from: 'node-revert', to: 'node-post-bid' },
    { from: 'node-post-bid', to: 'node-bot' },
    { from: 'node-post-ask', to: 'node-bot' },
    { from: 'node-bot', to: 'node-spread' },
    { from: 'node-bot', to: 'node-inventory' },
    { from: 'node-bot', to: 'node-vpin' },
    { from: 'node-spread', to: 'node-profit' },
    { from: 'node-profit', to: 'node-bankroll' },
    { from: 'node-vpin', to: 'node-kill' }
];

function initSvgConnections() {
    const svg = document.getElementById('connections');
    
    function drawLines() {
        svg.innerHTML = '';
        const svgRect = svg.getBoundingClientRect();
        
        edges.forEach(edge => {
            const fromEl = document.getElementById(edge.from);
            const toEl = document.getElementById(edge.to);
            if (!fromEl || !toEl) return;

            const fromPos = getNodeCenter(fromEl, svgRect);
            const toPos = getNodeCenter(toEl, svgRect);

            const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('x1', fromPos.x);
            line.setAttribute('y1', fromPos.y);
            line.setAttribute('x2', toPos.x);
            line.setAttribute('y2', toPos.y);
            line.setAttribute('class', 'conn-line');
            svg.appendChild(line);
        });
    }

    drawLines();
    window.addEventListener('resize', drawLines);
    setTimeout(drawLines, 100);
}

function getNodeCenter(el, svgRect) {
    const rect = el.getBoundingClientRect();
    return {
        x: rect.left + rect.width / 2 - svgRect.left,
        y: rect.top + rect.height / 2 - svgRect.top
    };
}

let stompClient = null;

function connectWebSocket() {
    const socket = new SockJS('/spreadbot-ws');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;
    
    stompClient.connect({}, function (frame) {
        stompClient.subscribe('/topic/stats', function (message) {
            handleStats(JSON.parse(message.body));
        });
        stompClient.subscribe('/topic/events', function (message) {
            handleEvent(JSON.parse(message.body));
        });
            stompClient.subscribe('/topic/ai-insights', function(msg) {
                const insight = JSON.parse(msg.body);
                handleAiInsight(insight);
            });
    }, function(error) {
        setTimeout(connectWebSocket, 5000);
    });
}

function handleStats(stats) {
    const setVal = (id, val) => {
        const el = document.getElementById(id);
        if (el) el.innerText = val;
    };

    setVal('hdr-latency', stats.latency + 'ms');
    setVal('hdr-ops', stats.ops.toLocaleString());
    setVal('hdr-fill', stats.fillRate + '%');
    setVal('hdr-dps', '$' + stats.dps);
    setVal('hdr-dph', '$' + Math.floor(stats.dph));
    setVal('hdr-uptime', stats.uptime || '00:00:00');
    setVal('hdr-trades', stats.trades);
    setVal('hdr-vpin', stats.vpin);
    setVal('val-vpin', stats.vpin);

    const confPct = stats.aiConfidence != null ? Math.round(stats.aiConfidence * 100) : null;
    const accEl = document.getElementById('val-accuracy');
    if (accEl) {
        accEl.innerText = confPct != null ? confPct + '%' : '--';
        accEl.className = 'node-val ' + (confPct == null ? '' : confPct >= 70 ? 'conf-green' : confPct >= 50 ? 'conf-yellow' : 'conf-red');
    }
    if (confPct != null) {
        confidenceHistory.push(confPct);
        if (confidenceHistory.length > 30) confidenceHistory.shift();
        drawSparkline();
    }

    lastStatsTime = Date.now();
    updateSkippedBadge(stats.skippedMarkets || 0);

    setVal('val-ollama', stats.claudeModel ? stats.claudeModel + ': SYNCED' : 'READY');
    setVal('val-sentiment', stats.sentiment != null ? Number(stats.sentiment).toFixed(2) : '--');
    setVal('val-bot-trades', stats.trades);
    setVal('ftr-trades', stats.trades);
    setVal('ftr-fill', '$' + Number(stats.totalVolume).toLocaleString());
    setVal('val-spreads', '$' + Number(stats.spread || 0).toFixed(3));
    const formatPnL = (val) => {
        const v = Number(val);
        return (v >= 0 ? '+' : '-') + '$' + Math.abs(v).toFixed(2);
    };
    const getPnLClass = (val) => (Number(val) >= 0 ? 'green' : 'loss-red');

    const profit = stats.profit;
    const profitEl = document.getElementById('val-profit');
    if (profitEl) {
        profitEl.innerText = formatPnL(profit);
        profitEl.className = 'node-val ' + getPnLClass(profit);
    }
    const winsEl = document.getElementById('val-gross-wins');
    if (winsEl) winsEl.innerText = '+$' + Math.abs(Number(stats.grossWins || 0)).toFixed(2);
    const lossesEl = document.getElementById('val-gross-losses');
    if (lossesEl) lossesEl.innerText = '-$' + Math.abs(Number(stats.grossLosses || 0)).toFixed(2);

    const bankrollFormatted = '$' + Number(stats.bankroll).toLocaleString();
    setVal('val-bankroll', bankrollFormatted);
    setVal('val-bankroll-node', bankrollFormatted);
    
    const sessionPnL = stats.sessionPnL;
    const sPnlHdr = document.getElementById('val-session-pnl-hdr');
    if (sPnlHdr) {
        sPnlHdr.innerText = formatPnL(sessionPnL);
        sPnlHdr.className = 'value ' + getPnLClass(sessionPnL);
    }
    const sPnlFtr = document.getElementById('val-session-pnl-ftr');
    if (sPnlFtr) {
        sPnlFtr.innerText = formatPnL(sessionPnL);
        sPnlFtr.className = 'session-pnl ' + getPnLClass(sessionPnL);
    }
    
    setVal('val-vpin', stats.vpin);
    
    let inv = stats.inventory;
    const invEl = document.getElementById('val-inventory');
    if (invEl) invEl.innerText = (inv > 0 ? '+' : '') + inv;

    _latestTradeHistory = stats.history || [];
    renderPositions(stats.positions || []);
    renderHistory(_latestTradeHistory);
    if (stats.arbPairs) renderArbPairs(stats.arbPairs);
}

function switchTab(tab) {
    ['open', 'history', 'arb'].forEach(t => {
        const c = document.getElementById('container-' + t);
        const b = document.getElementById('tab-' + t);
        if (c) c.style.display = 'none';
        if (b) b.classList.remove('active');
    });

    document.getElementById('container-' + tab).style.display = 'block';
    document.getElementById('tab-' + tab).classList.add('active');

    // Show export buttons only on history tab
    const isHistory = tab === 'history';
    document.getElementById('btn-copy-trades')?.classList.toggle('visible', isHistory);
    document.getElementById('btn-export-csv')?.classList.toggle('visible', isHistory);
}

function renderHistory(trades) {
    const body = document.getElementById('history-body');
    if (!body) return;
    
    body.innerHTML = '';
    
    if (!trades || trades.length === 0) {
        body.innerHTML = '<tr><td colspan="5" style="text-align:center; opacity:0.3; padding:25px; font-size:9px; letter-spacing:1px;">NO TRADE HISTORY</td></tr>';
        return;
    }

    // Reverse to show latest first
    [...trades].reverse().forEach(t => {
        const row = document.createElement('tr');
        const time = new Date(t.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        const pnl = t.realizedPnL || 0;
        const pnlClass = pnl > 0 ? 'pnl-pos' : (pnl < 0 ? 'pnl-neg' : '');
        
        row.innerHTML = `
            <td style="color: var(--accent-cyan); font-size: 11px; font-weight: 700; font-family: 'JetBrains Mono';">${time}</td>
            <td style="font-weight: 700;">${t.asset}</td>
            <td class="${t.side === 'BUY' ? 'pos-buy' : 'pos-sell'}">${t.side}</td>
            <td>${t.qty}</td>
            <td style="font-family: 'JetBrains Mono';">$${Number(t.price).toFixed(3)}</td>
            <td class="${pnlClass}" style="font-family: 'JetBrains Mono';">
                ${(pnl >= 0 ? '+' : '-')}$${Math.abs(pnl).toFixed(2)}
            </td>
        `;
        body.appendChild(row);
    });
}

function renderArbPairs(arbData) {
    const openCount = arbData.openCount || 0;
    const locked    = arbData.totalLocked  || '0.0000';
    const settled   = arbData.totalSettled || '0.0000';

    const setVal = (id, val) => { const el = document.getElementById(id); if (el) el.innerText = val; };
    setVal('arb-open-count', openCount);
    setVal('arb-locked',  '+$' + locked);
    setVal('arb-settled', '+$' + settled);

    const body = document.getElementById('arb-body');
    if (!body) return;
    body.innerHTML = '';

    const pairs = arbData.pairs || [];
    if (!pairs.length) {
        body.innerHTML = '<tr><td colspan="6" style="text-align:center;opacity:0.3;padding:18px;font-size:9px;letter-spacing:1px;">NO ACTIVE ARB PAIRS — SCANNING SHORT-DURATION MARKETS</td></tr>';
        return;
    }

    [...pairs].reverse().forEach(p => {
        const tr = document.createElement('tr');
        const status = p.resolved
            ? `<span class="pnl-pos">SETTLED +$${p.settledProfit}</span>`
            : `<span style="color:var(--teal)">OPEN</span>`;
        tr.innerHTML = `
            <td style="color:var(--text-dim);font-size:9px">${p.pairId}</td>
            <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${p.question}</td>
            <td class="pos-buy">$${p.yesCost}</td>
            <td class="pos-buy">$${p.noCost}</td>
            <td class="pnl-pos">+$${p.lockedProfit}</td>
            <td>${status}</td>
        `;
        body.appendChild(tr);
    });
}

function renderPositions(positions) {
    const body = document.getElementById('positions-body');
    if (!body) return;
    
    body.innerHTML = '';
    
    if (!positions || positions.length === 0) {
        body.innerHTML = '<tr><td colspan="6" style="text-align:center; opacity:0.3; padding:25px; font-size:9px; letter-spacing:1px;">NO OPEN POSITIONS</td></tr>';
        return;
    }
    
    positions.forEach(pos => {
        const tr = document.createElement('tr');
        const pnl = pos.pnl || 0;
        const pnlClass = pnl >= 0 ? 'pnl-pos' : 'pnl-neg';
        const sideClass = pos.side === 'BUY' ? 'pos-buy' : 'pos-sell';
        
        tr.innerHTML = `
            <td>${pos.ticker}</td>
            <td class="${sideClass}">${pos.side}</td>
            <td>${pos.size}</td>
            <td>$${Number(pos.entryPrice).toFixed(3)}</td>
            <td class="mark-price">$${Number(pos.lastPrice).toFixed(3)}</td>
            <td class="${pnlClass}">${(pnl >= 0 ? '+$' : '-$')}${Math.abs(pnl).toFixed(2)}</td>
        `;
        body.appendChild(tr);
    });
}

// Latest trade history snapshot — kept in sync via handleStats
let _latestTradeHistory = [];

function handleEvent(event) {
    addLogEntry(event);
    triggerNodePulse(event.type);

    if (event.type === 'SOLAR_UPDATE') {
        const val = event.message.split(': ')[1];
        if (val) document.getElementById('solar-multiplier').innerText = val + 'x';
    }
    if (event.type === 'TEST_FILL' && event.message.includes('Phase:')) {
        const phase = event.message.split('Phase: ')[1]?.replace(']', '');
        if (phase) document.getElementById('market-phase').innerText = phase;
    }
    if (event.type === 'KILL_SWITCH') {
        document.getElementById('node-kill')?.classList.add('kill-active');
    }
    if (event.type === 'STOP_LOSS' || event.type === 'TAKE_PROFIT') {
        triggerNodePulse('node-profit');
    }
    // ARB ENGINE node: update gap display
    if (event.type === 'WEATHER_ARB') {
        const gapEl = document.getElementById('val-weather');
        if (gapEl && event.data != null) gapEl.innerText = '+' + event.data + 'pt';
        flashStratStatus('strat-status-structural', 'FIRED');
    }
    // Gabagool events
    if (event.type === 'GABAGOOL_ENTRY') {
        triggerNodePulse('WEATHER_ARB');
        const gapEl = document.getElementById('val-weather');
        if (gapEl && event.data != null) gapEl.innerText = '+' + event.data + 'pt';
        flashStratStatus('strat-status-gabagool', 'ENTERED');
    }
    if (event.type === 'GABAGOOL_SETTLE') {
        triggerNodePulse('SPREAD');
        flashStratStatus('strat-status-gabagool', 'SETTLED');
    }
    if (event.type === 'TRADE' || event.type === 'AUDIT_PASS') {
        flashStratStatus('strat-status-weather', 'FIRED');
    }
    if (event.type === 'TEST_FILL') {
        flashStratStatus('strat-status-mm', 'FILLED');
    }
}

function flashStratStatus(id, label) {
    const el = document.getElementById(id);
    if (!el) return;
    const prev = el.innerText;
    const prevClass = el.className;
    el.innerText = label;
    el.className = 'strat-status fired';
    setTimeout(() => {
        el.innerText = prev;
        el.className = prevClass;
    }, 3000);
}

/* ─── Export helpers ──────────────────────────────────────────────────────── */

function copyTrades() {
    if (!_latestTradeHistory.length) { alert('No trades to copy.'); return; }
    const header = 'TIME\tASSET\tSIDE\tQTY\tPRICE\tREALIZED P&L';
    const rows = [..._latestTradeHistory].reverse().map(t => {
        const time = new Date(t.timestamp).toLocaleTimeString();
        const pnl  = (t.realizedPnL >= 0 ? '+' : '') + Number(t.realizedPnL).toFixed(4);
        return [time, t.asset, t.side, t.qty, Number(t.price).toFixed(4), pnl].join('\t');
    });
    navigator.clipboard.writeText([header, ...rows].join('\n'))
        .then(() => {
            const btn = document.getElementById('btn-copy-trades');
            if (btn) { btn.innerText = '✓ COPIED'; setTimeout(() => { btn.innerHTML = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5" y="5" width="9" height="9" rx="1.5"/><path d="M11 5V3.5A1.5 1.5 0 0 0 9.5 2h-6A1.5 1.5 0 0 0 2 3.5v6A1.5 1.5 0 0 0 3.5 11H5"/></svg> COPY'; }, 2000); }
        })
        .catch(() => alert('Clipboard unavailable — try EXPORT CSV instead.'));
}

function exportCSV() {
    if (!_latestTradeHistory.length) { alert('No trades to export.'); return; }
    const header = 'timestamp,asset,side,qty,price,realized_pnl';
    const rows = [..._latestTradeHistory].reverse().map(t =>
        [t.timestamp, `"${t.asset}"`, t.side, t.qty,
         Number(t.price).toFixed(6), Number(t.realizedPnL).toFixed(6)].join(',')
    );
    const csv  = [header, ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `spread-engine-trades-${new Date().toISOString().slice(0,10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
}

function addLogEntry(event) {
    const stream = document.getElementById('event-stream');
    if (!stream) return;
    const now = new Date();
    const timeStr = now.toTimeString().split(' ')[0];
    
    const div = document.createElement('div');
    div.className = 'log-entry';
    
    let msgClass = '';
    if (event.type === 'SPREAD' || event.type === 'MEAN_REVERT' || event.type === 'WEATHER_ARB'
        || event.type === 'TRADE' || event.type === 'TEST_FILL' || event.type === 'AUDIT_PASS'
        || event.type === 'TAKE_PROFIT' || event.type === 'GABAGOOL_ENTRY' || event.type === 'GABAGOOL_SETTLE') {
        msgClass = 'green';
    } else if (event.type === 'SIM_WEATHER_ARB') {
        msgClass = 'sim-event'; // grey — simulation noise, not a real trade signal
    } else if (event.type === 'SOLAR_UPDATE') {
        msgClass = 'blue';
    } else if (event.type === 'AUDIT_VETO' || event.type === 'STOP_LOSS') {
        msgClass = 'loss-red';
    } else if (event.type === 'KILL_SWITCH') {
        msgClass = 'loss-red';
    }
    
    div.innerHTML = `<span class="log-time">${timeStr}</span> <span class="log-event ${msgClass}">${event.message}</span>`;
    
    stream.appendChild(div);
    if (stream.children.length > 50) {
        stream.removeChild(stream.firstChild);
    }
    stream.scrollTop = stream.scrollHeight;
}

function handleAiInsight(insight) {
    // Confidence Meter
    document.getElementById('conf-meter').style.width = insight.confidence + '%';
    document.getElementById('conf-value').innerText = insight.confidence + '%';

    // Sentiment Pill
    const sPill = document.getElementById('sentiment-pill');
    if (insight.sentiment > 0.6) {
        sPill.innerText = 'BULLISH';
        sPill.className = 'sentiment-pill sentiment-bullish';
    } else if (insight.sentiment < 0.4) {
        sPill.innerText = 'BEARISH';
        sPill.className = 'sentiment-pill sentiment-bearish';
    } else {
        sPill.innerText = 'NEUTRAL';
        sPill.className = 'sentiment-pill';
    }

    // TA Badges
    const rBadge = document.getElementById('rsi-badge');
    rBadge.innerText = 'RSI ' + insight.rsi.toFixed(0);
    if (insight.rsi > 70 || insight.rsi < 30) rBadge.classList.add('active');
    else rBadge.classList.remove('active');

    const mBadge = document.getElementById('macd-badge');
    mBadge.innerText = 'MACD ' + insight.macd;
    if (insight.macd === 'BULL') mBadge.classList.add('active');
    else mBadge.classList.remove('active');

    // Trigger Pulse from Brain Node
    triggerNodePulse('WEKA_INSIGHT');
}

function triggerNodePulse(type) {
    switch(type) {
        case 'WEKA_INSIGHT':
            pulseNode('node-weka', 0);
            spawnParticle('node-weka', 'node-bot');
            break;
        case 'TRADE': 
        case 'TEST_FILL':
            spawnParticle('node-scanner', 'node-post-bid', () => {
                pulseNode('node-post-bid', 0);
                spawnParticle('node-post-bid', 'node-bot', () => {
                    pulseNode('node-bot', 0);
                });
            });
            break;
        case 'SPREAD': 
            spawnParticle('node-bot', 'node-spread', () => {
                pulseNode('node-spread', 0);
                spawnParticle('node-spread', 'node-profit', () => {
                    pulseNode('node-profit', 0);
                });
            });
            break;
        case 'VPIN': 
            spawnParticle('node-bot', 'node-vpin', () => {
                pulseNode('node-vpin', 0);
                if (parseFloat(document.getElementById('val-vpin').innerText) > 0.3) {
                    spawnParticle('node-vpin', 'node-kill', () => {
                        pulseNode('node-kill', 0);
                    });
                }
            });
            break;
        case 'MEAN_REVERT': 
            spawnParticle('node-revert', 'node-post-bid', () => {
                pulseNode('node-post-bid', 0);
            });
            break;
        case 'WEATHER_ARB': 
            spawnParticle('node-weather', 'node-post-ask', () => {
                pulseNode('node-post-ask', 0);
                spawnParticle('node-post-ask', 'node-bot', () => {
                    pulseNode('node-bot', 0);
                });
            });
            break;
        case 'INVENTORY': 
            spawnParticle('node-bot', 'node-inventory', () => {
                pulseNode('node-inventory', 0);
            });
            break;
    }
}

function spawnParticle(fromId, toId, callback) {
    const fromEl = document.getElementById(fromId);
    const toEl = document.getElementById(toId);
    if (!fromEl || !toEl) return;

    const svgRect = document.getElementById('connections').getBoundingClientRect();
    const fromPos = getNodeCenter(fromEl, svgRect);
    const toPos = getNodeCenter(toEl, svgRect);

    const dot = document.createElement('div');
    dot.className = 'ping-dot';
    dot.style.left = fromPos.x + 'px';
    dot.style.top = fromPos.y + 'px';
    document.querySelector('.graph-area').appendChild(dot);

    const duration = 600; 
    const startTime = performance.now();

    function animate(currentTime) {
        const elapsed = currentTime - startTime;
        const progress = Math.min(elapsed / duration, 1);

        const currentX = fromPos.x + (toPos.x - fromPos.x) * progress;
        const currentY = fromPos.y + (toPos.y - fromPos.y) * progress;

        dot.style.left = currentX + 'px';
        dot.style.top = currentY + 'px';

        if (progress < 1) {
            requestAnimationFrame(animate);
        } else {
            dot.remove();
            if (callback) callback();
        }
    }
    requestAnimationFrame(animate);
}

function pulseNode(id, delay) {
    setTimeout(() => {
        const el = document.getElementById(id);
        if (!el) return;
        el.classList.add('pulse');
        setTimeout(() => {
            el.classList.remove('pulse');
        }, 400);
    }, delay);
}

function drawSparkline() {
    const canvas = document.getElementById('weka-sparkline');
    if (!canvas || confidenceHistory.length < 2) return;
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const min = Math.min(...confidenceHistory);
    const max = Math.max(...confidenceHistory);
    const range = max - min || 1;

    const last = confidenceHistory[confidenceHistory.length - 1];
    const lineColor = last >= 70 ? '#00ff66' : last >= 50 ? '#ffcc00' : '#ff7171';

    ctx.beginPath();
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 1.5;
    ctx.shadowColor = lineColor;
    ctx.shadowBlur = 4;
    confidenceHistory.forEach((val, i) => {
        const x = (i / (confidenceHistory.length - 1)) * w;
        const y = h - ((val - min) / range) * (h - 2) - 1;
        i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    });
    ctx.stroke();
}

function initStaleChecker() {
    lastStatsTime = Date.now();
    setInterval(() => {
        const stale = lastStatsTime > 0 && (Date.now() - lastStatsTime) > 10000;
        document.body.classList.toggle('is-stale', stale);
        const badge = document.getElementById('stale-badge');
        if (badge) badge.style.display = stale ? 'flex' : 'none';
    }, 2000);
}

function updateSkippedBadge(count) {
    const badge = document.getElementById('skipped-badge');
    if (!badge) return;
    if (count > 0) {
        badge.innerText = count + ' SKIPPED';
        badge.style.display = 'flex';
    } else {
        badge.style.display = 'none';
    }
}
