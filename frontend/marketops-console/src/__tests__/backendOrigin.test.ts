import {
  BACKEND_PORT_ENVIRONMENT_VARIABLE,
  resolveBackendOrigin,
} from '../../tests/browser/backendOrigin.ts';

describe('browser backend origin', () => {
  it('uses the configured default port when the field is absent', () => {
    expect(resolveBackendOrigin({})).toBe('http://127.0.0.1:8080');
  });

  it('uses the port the backend is told to bind', () => {
    expect(resolveBackendOrigin({ [BACKEND_PORT_ENVIRONMENT_VARIABLE]: '9999' })).toBe(
      'http://127.0.0.1:9999',
    );
    expect(resolveBackendOrigin({ [BACKEND_PORT_ENVIRONMENT_VARIABLE]: '65535' })).toBe(
      'http://127.0.0.1:65535',
    );
  });

  it.each(['', '0', '08080', '65536', '99999', '9999 ', '80a', '-1', '127.0.0.1:9999'])(
    'rejects the port %j rather than guessing another origin',
    (port) => {
      expect(() => resolveBackendOrigin({ [BACKEND_PORT_ENVIRONMENT_VARIABLE]: port })).toThrow(
        'must be a TCP port number',
      );
    },
  );
});
