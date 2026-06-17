// smolvm "exec" function (classic-watchdog style).
// Reads the request envelope on stdin, writes a response envelope on stdout.
//
// stdin  : {"method","path","query","headers","body_base64"}
// stdout : {"status","headers","body_base64"}  (or "body" for plain text)

const chunks = [];
process.stdin.on("data", (c) => chunks.push(c));
process.stdin.on("end", () => {
  let req = {};
  try {
    req = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch (_) {
    /* empty / invalid stdin -> treat as empty request */
  }

  const reqBody = Buffer.from(req.body_base64 || "", "base64").toString("utf8");

  const payload = {
    message: "hello from a smolvm exec function",
    method: req.method,
    path: req.path,
    query: req.query,
    received: reqBody,
  };

  const resp = {
    status: 200,
    headers: { "content-type": "application/json" },
    body_base64: Buffer.from(JSON.stringify(payload)).toString("base64"),
  };

  process.stdout.write(JSON.stringify(resp));
});
