# ChunkyCloud Worker Node

Worker node for ChunkyCloud, a distributed rendering service for
[Chunky](https://github.com/chunky-dev/chunky).

This service runs headlessly, connects to a ChunkyCloud API server, polls for
merge tasks, downloads the required tile data, merges the tiles, uploads the
result, and marks the job as finished.

## Requirements

- Java 17 or newer
- A worker-node API key issued by a [ChunkyCloud server](https://github.com/ChunkyCloud/server)

## Getting started

1. Get a worker node API key from the ChunkyCloud server you want to connect to
2. Download the [latest release](https://github.com/ChunkyCloud/worker-node/releases/latest)
3. Run the jar

   ```bash
   java -jar workernode-v1.0.0.jar \
     --api-key your-worker-node-api-key
   ```

> [!IMPORTANT]
> You may only run a single process per API key. If you want to host multiple worker nodes, get a different API key for each of them.

## Configuration

| Option              | Default                       | Description                                                           |
| ------------------- | ----------------------------- | --------------------------------------------------------------------- |
| `--api`             | `https://api.chunkycloud.net` | ChunkyCloud API endpoint                                              |
| `--api-key`         | unset                         | Worker node API key                                                   |
| `--api-key-file`    | unset                         | File containing the worker node API key. Useful for container secrets |
| `--cache-directory` | `./cc_cache`                  | HTTP cache directory for downloaded scene resources                   |
| `--max-cache-size`  | `512`                         | Maximum HTTP cache size, in MB                                        |

The API key can also be provided through the `API_KEY` environment variable.

## Runtime directories

When no custom paths are provided, the worker node creates these directories in
the current working directory:

| Directory   | Purpose                                                        |
| ----------- | -------------------------------------------------------------- |
| `cc_cache`  | HTTP cache for API and scene downloads                         |
| `cc_chunky` | Chunky settings directory used by the internal Chunky instance |

For long-running nodes, mount these directories on persistent storage so resource
packs and cached scene files survive container restarts.

## Docker

```bash
docker run --rm \
  -e API_KEY=your-worker-node-api-key \
  -v chunkycloud-worker-node-data:/opt/cc-workernode/data \
  ghcr.io/chunkycloud/worker-node:latest
```

The container runs from `/opt/cc-workernode/data`, so the default runtime
directories are created inside the mounted data volume. Mounting a volume is recommended so that cached data (e.g. resource packs and scene files) are persisted across container restarts.

You can use a Docker secret to specify the API key, just launch with `--api-key-file /run/secrets/chunkycloud-node-token` instead of using an environment variable.

## Development

> [!CAUTION]
> Please **do not run customized worker nodes against leMaik's ChunkyCloud server**. Your contributions are very welcome, but please test them against a local ChunkyCloud server instance.

The project builds a self-contained jar file with its dependencies.

```bash
./gradlew build
```

The runnable jar file is written to:

```text
build/libs/workernode.jar
```

The main entry point is
`de.lemaik.chunkycloud.worker.Main`.

## License

This project is licensed under the GNU General Public License v3.0 or later.
See [LICENSE.txt](LICENSE.txt).
