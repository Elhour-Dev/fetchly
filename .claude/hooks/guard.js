// PreToolUse guard: blocks force-push and secret files. Exit 2 = block.
let raw = "";
process.stdin.on("data", (d) => (raw += d));
process.stdin.on("end", () => {
  let input = {};
  try { input = JSON.parse(raw); } catch { process.exit(0); }
  const tool = input.tool_name;
  const ti = input.tool_input || {};
  const block = (msg) => { console.error("Blocked: " + msg); process.exit(2); };

  const secret = /(^|[\\/])(cookies[^\\/]*\.txt|[^\\/]*\.cookies|local\.properties|\.env[^\\/]*|[^\\/]*\.(jks|keystore)|keystore-info\.txt)$/i;

  if (tool === "Bash") {
    const c = ti.command || "";
    if (/git\s+push\b[^;&|\n]*(\s--force\b|\s--force-with-lease\b|\s-f\b|\s\+\S)/.test(c))
      block("force-push is not allowed in this project.");
    if (/git\s+add\b/.test(c)) {
      const words = c.split(/\s+/);
      if (words.some((w) => secret.test(w.replace(/["']/g, ""))))
        block("do not add cookies, keys or local config to git.");
      if (/git\s+add\s+(-A|--all|\.)(\s|$)/.test(c) === false) { /* ok */ }
    }
  }

  if (tool === "Edit" || tool === "Write") {
    const p = ti.file_path || "";
    if (secret.test(p)) block("secret/local file: " + p);
  }
  process.exit(0);
});
