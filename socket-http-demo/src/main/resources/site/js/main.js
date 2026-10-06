import { connectMetrics } from "./metrics.js";
import { initTryIt } from "./tryit.js";

const root = document.documentElement;

document.getElementById("theme").addEventListener("click", () => {
  const dark = root.dataset.theme === "dark"
    || (!root.dataset.theme && matchMedia("(prefers-color-scheme: dark)").matches);
  root.dataset.theme = dark ? "light" : "dark";
  try { localStorage.setItem("theme", root.dataset.theme); } catch { /* storage may be blocked */ }
});

connectMetrics();
initTryIt();

fetch("/api/info")
  .then((r) => (r.ok ? r.json() : Promise.reject(new Error(r.statusText))))
  .then((info) => {
    document.getElementById("fact-model").textContent = info.concurrencyModel.toLowerCase().replaceAll("_", " ");
    document.getElementById("fact-java").textContent = `Java ${info.java}`;
  })
  .catch(() => {
    document.getElementById("fact-model").textContent = "unavailable";
    document.getElementById("fact-java").textContent = "unavailable";
  });
