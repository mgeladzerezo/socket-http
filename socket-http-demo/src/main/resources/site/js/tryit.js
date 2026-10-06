const out = () => document.getElementById("response");

async function send(spec, body) {
  const [method, target] = spec.split(" ");
  const started = performance.now();
  const box = out();
  box.classList.remove("error");
  box.textContent = `${method} ${target} ...`;
  try {
    const init = { method };
    if (body) {
      init.body = body;
      init.headers = { "Content-Type": "application/json" };
    }
    const response = await fetch(target, init);
    const text = await response.text();
    const ms = (performance.now() - started).toFixed(0);
    const headers = [...response.headers].map(([k, v]) => `${k}: ${v}`).join("\n");
    let pretty = text;
    try { pretty = JSON.stringify(JSON.parse(text), null, 2); } catch { /* not JSON */ }
    box.textContent = `${method} ${target}\n\nHTTP ${response.status} ${response.statusText} (${ms} ms)\n${headers}\n\n${pretty}`;
    if (!response.ok) box.classList.add("error");
  } catch (error) {
    box.classList.add("error");
    box.textContent = `${method} ${target}\n\nRequest failed: ${error.message}`;
  }
}

async function generateLoad(button, state) {
  const total = 300;
  const workers = 6;
  let sent = 0;
  let failed = 0;
  button.disabled = true;
  const paths = ["/api/hello", "/api/users", "/api/users/1", "/api/users/404", "/api/info", "/healthz"];
  const worker = async () => {
    while (sent < total) {
      const n = sent++;
      try {
        const r = await fetch(paths[n % paths.length]);
        await r.arrayBuffer();
      } catch {
        failed++;
      }
      if (n % 20 === 0) state.textContent = `${Math.min(sent, total)} / ${total}`;
    }
  };
  await Promise.all(Array.from({ length: workers }, worker));
  state.textContent = failed ? `done, ${failed} failed` : "done";
  button.disabled = false;
}

export function initTryIt() {
  for (const button of document.querySelectorAll("[data-req]")) {
    button.addEventListener("click", () => send(button.dataset.req, button.dataset.body));
  }
  const load = document.getElementById("load");
  load.addEventListener("click", () => generateLoad(load, document.getElementById("load-state")));
}
