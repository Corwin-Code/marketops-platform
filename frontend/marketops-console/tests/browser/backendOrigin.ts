/** Environment field that Spring Boot binds as the backend's HTTP port. */
export const BACKEND_PORT_ENVIRONMENT_VARIABLE = 'SERVER_PORT';

/** The port application.yaml configures when the field is absent. */
export const DEFAULT_BACKEND_PORT = '8080';

const TCP_PORT = /^[1-9][0-9]{0,4}$/;

type Environment = Readonly<Record<string, string | undefined>>;

/**
 * Resolve the origin of the backend that the browser suite starts.
 *
 * The backend, the console build and the browser routes read the one field, so
 * an isolated run can move all three off a port that another process holds.
 */
export function resolveBackendOrigin(environment: Environment = process.env): string {
  const configured = environment[BACKEND_PORT_ENVIRONMENT_VARIABLE];
  if (configured === undefined) {
    return `http://127.0.0.1:${DEFAULT_BACKEND_PORT}`;
  }
  if (!TCP_PORT.test(configured) || Number(configured) > 65_535) {
    throw new Error(`${BACKEND_PORT_ENVIRONMENT_VARIABLE} must be a TCP port number`);
  }
  return `http://127.0.0.1:${configured}`;
}
