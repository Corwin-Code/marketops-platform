import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../App';

describe('console configuration boundary', () => {
  it('renders only missing variable names and makes no request', () => {
    const fetchImpl = vi.fn();

    render(
      <App
        env={{ VITE_MARKETOPS_ENVIRONMENT: 'value-that-must-not-be-rendered' }}
        fetchImpl={fetchImpl as unknown as typeof fetch}
      />,
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('VITE_MARKETOPS_API_BASE_URL')).toBeInTheDocument();
    expect(screen.queryByText('value-that-must-not-be-rendered')).not.toBeInTheDocument();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('names every blank setting and still makes no request', () => {
    const fetchImpl = vi.fn();

    render(
      <App
        env={{ VITE_MARKETOPS_API_BASE_URL: ' ', VITE_MARKETOPS_ENVIRONMENT: '' }}
        fetchImpl={fetchImpl as unknown as typeof fetch}
      />,
    );

    expect(screen.getByText('VITE_MARKETOPS_API_BASE_URL')).toBeInTheDocument();
    expect(screen.getByText('VITE_MARKETOPS_ENVIRONMENT')).toBeInTheDocument();
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
