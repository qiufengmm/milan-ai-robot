# Local Elasticsearch with IK

This project uses Elasticsearch **9.4.2** with the matching **analysis-ik 9.4.2** plugin. The image fixes the official Elasticsearch image digest and verifies the plugin's SHA-512 before installation. The [IK maintainers](https://github.com/infinilabs/analysis-ik) publish the [plugin package](https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.4.2.zip) and its [checksum](https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.4.2.zip.sha512).

Run from the repository root:

```powershell
curl.exe --fail --location --retry 3 --retry-all-errors --continue-at - --max-time 300 --output docker/elasticsearch/elasticsearch-analysis-ik-9.4.2.zip https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.4.2.zip
docker compose -f docker/elasticsearch/compose.yml build
docker compose -f docker/elasticsearch/compose.yml up -d
docker compose -f docker/elasticsearch/compose.yml ps
Invoke-RestMethod 'http://127.0.0.1:19200/_cluster/health'
```

The downloaded archive is ignored by Git. Building from the local archive allows interrupted downloads to resume and avoids a second download inside Docker; the Dockerfile checks the official SHA-512 before installing it.

The container is `milan-rag-elasticsearch`, the Compose project is `milan-rag`, the data volume is `milan_rag_elasticsearch_data_v1`, and the network is `milan_rag_elasticsearch_network`. These resources are independent of other projects and of PostgreSQL. Only `127.0.0.1:19200` is published; port 9300 is not published. Authentication and TLS are disabled for this local development endpoint. The JVM heap is 512 MB and the container memory limit is 1536 MB.

The backend configuration is:

```yaml
rag:
  elasticsearch:
    enabled: true
    url: http://127.0.0.1:19200
    index-name: milan_rag_chunks_v1
    analyzer: ik_max_word
    search-analyzer: ik_smart
    timeout-seconds: 10
```

The application calls `EsChunkService.ensureIndex()` before synchronization; the container does not create or reset business indexes. A single-node cluster may be yellow when an index requests replicas, because replicas cannot be assigned to the same node as their primary. The health check accepts yellow or green.

To pause the service while preserving all data:

```powershell
docker compose -f docker/elasticsearch/compose.yml stop
```

Do not remove its volume or reset an index to follow tutorial experiments. Version upgrades require a separately planned migration and a matching IK plugin package.
