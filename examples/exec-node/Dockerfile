FROM node:20-alpine
WORKDIR /app
COPY handler.js .
# The machine must stay alive between `start` and `exec`, so PID 1 is a sleeper.
# The plugin runs the function per-request via `exec_command` (see route.json),
# feeding the request on stdin and reading the response on stdout.
CMD ["sleep", "infinity"]
