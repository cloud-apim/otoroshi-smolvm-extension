// A plain HTTP server. In "service-via-exec" mode the image's CMD is a sleeper, and the
// SmolMachine plugin starts THIS server with an `exec` (spec.launch_command) once the
// instance is created, then reverse-proxies requests to it.

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
          message: "hello from a smolvm service launched via exec",
          method: req.method,
          url: req.url,
          received: body,
        })
      );
    });
  })
  .listen(port, () => console.log(`listening on ${port}`));
