/**
 * Provider-neutral server-side generation boundary.
 *
 * Implementations receive only validated, feature-specific FitDesi inputs. They
 * must not expose provider credentials, configuration, or raw provider errors.
 */
export class AiProvider {
  async generateStructured(_request) {
    throw new TypeError("AI provider must implement generateStructured().");
  }
}
