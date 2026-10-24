FROM golang:1.27.3 AS build
WORKDIR /src
COPY go.mod go.sum ./
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/mock-embedder ./cmd/mock-embedder

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/mock-embedder /mock-embedder
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/mock-embedder"]
