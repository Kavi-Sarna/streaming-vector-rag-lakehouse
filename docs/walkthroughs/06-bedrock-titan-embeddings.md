# Bedrock Titan embeddings

`make flink-job` still calls the local mock embedder. That vector is 384 dimensions. Titan Text Embeddings V2 is the other client in the same job. It asks Bedrock for 1024 dimensions with normalization turned on. The model id is `amazon.titan-embed-text-v2:0`, in `us-east-1`.

The local submit script does not select it. A task that has AWS credentials and permission to invoke that model selects it with:

```sh
--embedder=bedrock --bedrock-region=us-east-1
```

The ordering rule is unchanged. A vector that finishes after a newer version of the same document is dropped. `make test` checks the Titan request body and that a vector of any other width is rejected. This does not call Bedrock and does not start the demo cluster.
