// smolvm "service" function: a long-running HTTP server.
// Otoroshi reverse-proxies the incoming request to this server (running inside
// the micro-VM, on a forwarded port). Streaming and binary work natively.

const http = require("http");
const port = parseInt(process.env.PORT || "8080", 10);

http
  .createServer((req, res) => {
    if (req.url === "/health") {
      res.writeHead(200, { "content-type": "text/plain" });
      return res.end("ok");
    }
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          message: "hello from a smolvm service function",
          method: req.method,
          url: req.url,
          received: body,
        })
      );
    });
  })
  .listen(port, () => console.log(`listening on ${port}`));
