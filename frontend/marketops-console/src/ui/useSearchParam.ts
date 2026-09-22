import { useCallback } from 'react';
import { useSearchParams } from 'react-router';

/**
 * One value kept in the address bar, so a page, filter or tab survives going
 * to a detail and back, a reload, and a shared link.
 *
 * Setting a value replaces the history entry rather than adding one, so the
 * browser's back button still leaves the page instead of undoing each click.
 */
export function useSearchParam(
  key: string,
): readonly [string | undefined, (value: string | undefined) => void] {
  const [params, setParams] = useSearchParams();
  const value = params.get(key) ?? undefined;
  const set = useCallback(
    (next: string | undefined) => {
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          if (next === undefined || next === '') {
            updated.delete(key);
          } else {
            updated.set(key, next);
          }
          return updated;
        },
        { replace: true },
      );
    },
    [key, setParams],
  );
  return [value, set] as const;
}

/** A 1-based page number kept in the address bar; anything unreadable is page 1. */
export function usePageParam(key = 'page'): readonly [number, (page: number) => void] {
  const [raw, setRaw] = useSearchParam(key);
  const parsed = raw === undefined ? 1 : Number.parseInt(raw, 10);
  const page = Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
  const set = useCallback(
    (next: number) => {
      setRaw(next <= 1 ? undefined : String(next));
    },
    [setRaw],
  );
  return [page, set] as const;
}

/**
 * Several address-bar values changed at once. Two separate setters called in
 * one handler would each start from the same old address and the second would
 * undo the first; a patch applies every key in one replacement.
 */
export function useSearchParamsPatch(): (
  patch: Readonly<Record<string, string | undefined>>,
) => void {
  const [, setParams] = useSearchParams();
  return useCallback(
    (patch) => {
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          for (const [key, value] of Object.entries(patch)) {
            if (value === undefined || value === '') updated.delete(key);
            else updated.set(key, value);
          }
          return updated;
        },
        { replace: true },
      );
    },
    [setParams],
  );
}
