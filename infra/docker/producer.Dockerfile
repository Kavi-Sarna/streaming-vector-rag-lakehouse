# The corpus stage downloads the markdown. The build context does not
# supply testdata/, so a CI runner with no local files still produces an
# image that can publish.
FROM python:3.12-slim-bookworm AS corpus
ENV PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /src
COPY scripts/requirements-seed.txt scripts/requirements-seed.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl \
    && pip install --no-cache-dir --root-user-action=ignore -r scripts/requirements-seed.txt \
    && rm -rf /var/lib/apt/lists/*
COPY scripts/ scripts/
RUN chmod +x scripts/*.sh \
    && ./scripts/seed-corpus.sh

FROM golang:1.27.3 AS build
WORKDIR /src
COPY go/go.mod go/go.sum ./
RUN go mod download
COPY go/ ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/producer ./cmd/producer

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/producer /producer
COPY --from=corpus /src/testdata /corpus
USER nonroot:nonroot
ENTRYPOINT ["/producer"]
CMD ["--input=/corpus"]
