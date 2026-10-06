const $ = (id) => document.getElementById(id);
const nf = new Intl.NumberFormat();
const HISTORY = 60;

const history = [];
let last = null;

function setState(kind, text) {
  const pill = $("stream-state");
  pill.className = `pill pill-${kind}`;
  pill.textContent = text;
  $("metrics-empty").hidden = kind === "live";
}

function micros(us) {
  if (us >= 1000) return `${(us / 1000).toFixed(us >= 10000 ? 0 : 1)} ms`;
  return `${us} µs`;
}

function duration(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}

function drawSpark() {
  const max = Math.max(1, ...history);
  const step = 300 / (HISTORY - 1);
  const offset = HISTORY - history.length;
  const points = history
    .map((v, i) => `${((i + offset) * step).toFixed(1)},${(60 - (v / max) * 56).toFixed(1)}`)
    .join(" ");
  $("spark-line").setAttribute("points", points);
}

function drawBars(responses) {
  const total = Object.values(responses).reduce((a, b) => a + b, 0);
  const bars = $("bars");
  if (!bars.children.length) {
    for (const cls of ["2xx", "3xx", "4xx", "5xx"]) {
      const row = document.createElement("div");
      row.className = "bar";
      row.dataset.cls = cls;
      row.innerHTML = `<span>${cls}</span><span class="bar-track"><span class="bar-fill c${cls[0]}"></span></span><span class="bar-count"></span>`;
      bars.append(row);
    }
  }
  for (const row of bars.children) {
    const n = responses[row.dataset.cls] ?? 0;
    row.querySelector(".bar-fill").style.width = total ? `${(n / total) * 100}%` : "0";
    row.querySelector(".bar-count").textContent = nf.format(n);
  }
}

function render(m) {
  const requests = m.requests;
  $("m-conn").textContent = nf.format(m.connections.active);
  $("m-conn-sub").textContent = `${nf.format(m.connections.accepted)} accepted, ${nf.format(m.connections.rejected)} rejected`;
  $("m-req").textContent = nf.format(requests.total);
  $("m-flight").textContent = nf.format(requests.inFlight);
  const lat = m.latencyMicros;
  $("m-p50").textContent = lat.count ? micros(lat.p50) : "–";
  $("m-lat-sub").textContent = lat.count ? `p99 ${micros(lat.p99)}, max ${micros(lat.max)}` : "no requests yet";
  $("fact-uptime").textContent = duration(m.uptimeMillis);

  if (last) {
    const dt = Math.max(1, m.uptimeMillis - last.uptimeMillis) / 1000;
    const rate = Math.max(0, (requests.total - last.requests.total) / dt);
    history.push(rate);
    if (history.length > HISTORY) history.shift();
    $("m-rate").textContent = `${rate.toFixed(rate < 10 ? 1 : 0)} per second`;
    drawSpark();
  }
  last = m;
  drawBars(requests.responses);
}

export function connectMetrics() {
  if (!("EventSource" in window)) {
    setState("down", "unsupported");
    return;
  }
  const source = new EventSource("/events/metrics");
  source.addEventListener("open", () => setState("live", "live"));
  source.addEventListener("metrics", (event) => {
    setState("live", "live");
    try {
      render(JSON.parse(event.data));
    } catch (error) {
      console.error("bad metrics event", error);
    }
  });
  source.addEventListener("error", () => {
    setState(source.readyState === EventSource.CLOSED ? "down" : "wait",
      source.readyState === EventSource.CLOSED ? "disconnected" : "reconnecting");
    last = null;
  });
}
