/**
 * The proof that the browser which asked for a token is the one redeeming it.
 *
 * An authorization code travelling through a redirect is visible to anything
 * that can read a URL — a browser history, a proxy log, an extension. Proof key
 * for code exchange makes an intercepted code useless on its own, because
 * redemption also needs a secret that never left this tab.
 *
 * The verifier is generated from the platform's cryptographic source rather
 * than from Math.random, which is predictable and would defeat the point.
 */

/** How many bytes of entropy a verifier carries. */
const VERIFIER_BYTES = 64;

/** How many bytes of entropy a state or nonce carries. */
const STATE_BYTES = 32;

/** One code challenge and the verifier that satisfies it. */
export interface PkcePair {
  /** The secret that stays in this tab. */
  readonly verifier: string;
  /** The value sent to the identity provider. */
  readonly challenge: string;
}

/** Encode bytes the way the specification requires: base64url, unpadded. */
export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A random, unguessable value of the requested length. */
export function randomValue(byteLength: number = STATE_BYTES): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

/**
 * Create a verifier and the challenge derived from it.
 *
 * The challenge is the SHA-256 of the verifier, which is what lets the identity
 * provider check the pair without ever holding the secret itself.
 */
export async function createPkcePair(): Promise<PkcePair> {
  const verifier = randomValue(VERIFIER_BYTES);
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return { verifier, challenge: base64UrlEncode(new Uint8Array(digest)) };
}
