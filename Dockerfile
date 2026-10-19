# Musebot Agents: one image that serves the page and runs the agents. No Node and no front-end build is needed.
#
#   docker build -t musebots .
#   docker run --rm -p 7070:7070 musebots        then open http://localhost:7070
#
# Settings (environment variables), all optional:
#   PORT               the web port                                    (default 7070)
#   MAX_PERFORMANCES   how many bands may play at once                 (default 8; each is sixteen agents)
#   ALLOWED_ORIGINS    extra web origins allowed to connect, comma separated (the site's own pages always may)
#   MARKOV_MODEL_SAX, MARKOV_MODEL_PIANO, MARKOV_MODEL_BASS, MARKOV_MODEL_DRUMS
#                      an instrument's solo model; by default the ones in models/, which the image includes
#   MARKOV_MODEL       a model for every instrument without its own
#   JAVA_OPTS          JVM options; the default uses up to 65% of the container's memory limit

# ---- build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew \
 && ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1g" :server:installDist

# ---- run ----
FROM eclipse-temurin:17-jre
# curl is for the health check below. The server runs as an ordinary user, in a home that is its working directory:
# the agent platform writes a few small files there.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --home-dir /app --uid 10001 musebots
WORKDIR /app
COPY --from=build --chown=musebots:musebots /src/server/build/install/server/ ./
# The learned solo models (ODbL; see models/README.md). A model that is missing is reported and that instrument solos by the rules.
COPY --chown=musebots:musebots models/ ./models/
USER musebots

ENV PORT=7070 \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError" \
    MARKOV_MODEL_SAX=/app/models/markov-sax.json \
    MARKOV_MODEL_PIANO=/app/models/markov-piano.json \
    MARKOV_MODEL_BASS=/app/models/markov-bass.json \
    MARKOV_MODEL_DRUMS=/app/models/markov-drums.json
# Only the web port is for the outside. The agent platform's own port (1099) is used inside the container and must not be published.
EXPOSE 7070
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT}/health" || exit 1

ENTRYPOINT ["bin/server"]
