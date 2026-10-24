# Local infrastructure

Start the backing services, check them, then stop them. This does not call AWS.

```sh
make docker-up
make docker-health
make docker-down
```

`make docker-up` waits until every service is healthy. `make docker-health` checks the same stack from the host:

- Redpanda answers on `localhost:9092`, and topic `document.events.v1` exists.
- Silo answers on `localhost:9000`, and bucket `lakehouse` exists. The console is `localhost:9001`. The user is `minio` and the password is `minio12345`.
- Redis Stack answers `PING` on `localhost:6379`.
- Flink at `http://localhost:8081` has one TaskManager and no submitted job.
- `POST http://localhost:8091/embed` with `{"text":"hello"}` returns 384 dimensions.
- Prometheus is ready at `http://localhost:9090`. It scrapes the host producer on port 9095 once that process is running.
- Grafana is ready at `http://localhost:3000`. The home dashboard is Producer. No login is required.

```sh
curl -s http://127.0.0.1:8091/embed \
  -H 'content-type: application/json' \
  -d '{"text":"hello"}'
```

These credentials and ports are for the laptop only. `make docker-down` stops the containers and keeps their volumes.
