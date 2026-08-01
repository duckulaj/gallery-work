# Gallery App — Complete Modular VS Code Edition

This archive contains one Maven workspace and one Java entry point for the complete application:

```text
gallery-app/src/main/java/com/hawkins/gallery/GalleryApplication.java
```

Running that class starts one Spring Boot application context containing every Java module: web UI, indexing, persistence, AI enrichment/search, face recognition, review workflow and background schedulers.

## Modules assembled by the main application

- `gallery-domain` — entities, enums and shared events
- `gallery-persistence` — Spring Data JPA repositories
- `gallery-core` — indexing, albums, thumbnails and EXIF handling
- `gallery-ai` — Ollama enrichment, embeddings, search and face integration
- `gallery-review` — NSFW queue, review decisions and quarantine workflow
- `gallery-web` — MVC controllers, Thymeleaf templates, CSS and JavaScript
- `gallery-app` — the only executable module, central configuration and Flyway baseline
- `face-service` — separate Python service started by Docker Compose

`gallery-app/pom.xml` declares all six Java feature modules directly. `GalleryApplication` explicitly scans all `com.hawkins.gallery` components, entities and repositories.

## First-time prerequisites

Install:

- Java 21
- Maven 3.8.7 or later
- VS Code with the recommended extensions when prompted
- Docker with Docker Compose
- Ollama

Pull the local AI models once:

```bash
ollama pull gemma3:4b
ollama pull mxbai-embed-large
ollama pull minicpm-v:8b
```

Ensure Ollama is running:

```bash
ollama serve
```

## Open and run in VS Code

1. Extract this ZIP into a new directory.
2. In VS Code choose **File → Open Folder** and select the extracted `gallery-work` directory.
3. Allow the Java/Maven extensions to import the multi-module project.
4. Open **Run and Debug**.
5. Select **Run Complete Gallery App**.
6. Press **F5**.

The VS Code profile runs `scripts/start-infrastructure.sh` first. That starts:

- PostgreSQL 16 with pgvector on port `5432`
- The Python face/NSFW service on port `8082`

It then launches `com.hawkins.gallery.GalleryApplication`. Flyway creates the complete fresh schema automatically.

## Command-line alternative

From the extracted project root:

```bash
./run-gallery.sh
```

Or start infrastructure and Java separately:

```bash
./scripts/start-infrastructure.sh
mvn -pl gallery-app -am spring-boot:run
```

Build the executable JAR:

```bash
./build.sh
java -jar gallery-app/target/gallery-app-1.0.0-SNAPSHOT.jar
```

## Application addresses

- Gallery: `http://localhost:8080/`
- Review workspace: `http://localhost:8080/review`
- Health: `http://localhost:8080/actuator/health`
- Face-service health: `http://localhost:8082/health`

## Fresh database warning

Flyway contains one baseline migration:

```text
gallery-app/src/main/resources/db/migration/V1__initialise_gallery_database.sql
```

It is intended for a new database. The included PostgreSQL Docker volume is new on first start. To deliberately destroy and recreate the development database:

```bash
docker compose down -v
docker compose up -d postgres
```

That command deletes all gallery database data stored in the Docker volume.

## Configuration

Defaults are in `gallery-app/src/main/resources/application.yml`. They can be overridden without editing source code:

- `GALLERY_DB_URL`
- `GALLERY_DB_USERNAME`
- `GALLERY_DB_PASSWORD`
- `FACE_SERVICE_URL`
- `OLLAMA_BASE_URL`

The included VS Code launch profile supplies the development defaults.
