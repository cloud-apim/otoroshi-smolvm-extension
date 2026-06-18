FROM node:22-alpine
WORKDIR /app
COPY server.js .
ENV PORT=8080
EXPOSE 8080
# CMD is a sleeper on purpose: the HTTP server is NOT started at boot. The SmolMachine
# plugin launches it later via `exec` (spec.launch_command), then proxies to the forwarded
# port. This demonstrates "the exec launches the HTTP server" on a kept-alive micro-VM.
CMD ["sleep", "infinity"]
