# Review integration notes

The review workspace is now a first-class Maven module rather than an additive patch.

## Automatic queue integration

`gallery-core` publishes `AssetIndexedEvent` after creating an asset and its initial metadata. `gallery-review` handles the event after transaction commit and calls:

```java
reviewQueueService.enqueue(assetId, JobType.NSFW, 40, false);
```

This removes the old requirement to manually call the queue from every importer. Future importers only need to use the normal core asset-creation workflow or publish the same event after committing an externally created asset.

## Routes

- `/review` — integrated queue/review workspace.
- `/assets/{id}/thumbnail` — thumbnail route used by the workspace.

## Quarantine

The integrated workflow records original and quarantine paths. Normal gallery views should exclude quarantined assets or tolerate a moved original. The review workspace can restore the physical file using the stored history.

## Configuration

Runtime settings are in `gallery-app/src/main/resources/application.yml` under:

```yaml
app:
  ai:
    nsfw: ...
  review:
    quarantine-root: ./data/gallery/quarantine
```
