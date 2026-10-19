import { Container, getContainer } from "@cloudflare/containers";

/** The Musebots server: the page, the WebSocket and the agents, in the container built from the repository's Dockerfile. */
export class Band extends Container {
  defaultPort = 7070;
  // Asleep, it costs nothing; the first visitor after that waits while Java starts. A performance's WebSocket messages may not
  // count as activity, so this is kept well above the length of a performance.
  sleepAfter = "20m";

  constructor(ctx, env) {
    super(ctx, env);
    this.envVars = { PORT: "7070", MAX_PERFORMANCES: env.MAX_PERFORMANCES, ALLOWED_ORIGINS: env.ALLOWED_ORIGINS };
  }
}

export default {
  async fetch(request, env) {
    // Every request, the WebSocket included, goes to the same single container.
    return getContainer(env.BAND).fetch(request);
  },
};
