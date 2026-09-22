import { Button, Result } from 'antd';
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { pages, product, shell as text } from '../i18n/zh/shell';
import { ROUTES } from '../layout/navigation';

/** A path the console does not serve. */
export function NotFoundPage(): React.JSX.Element {
  const navigate = useNavigate();
  useEffect(() => {
    document.title = `${pages.notFound} · ${product.name}`;
  }, []);
  return (
    <div data-state="not-found">
      <Result
        status="404"
        title={pages.notFound}
        subTitle={text.notFoundDescription}
        extra={
          <Button
            type="primary"
            onClick={() => {
              void navigate(ROUTES.pricingQueue);
            }}
          >
            {text.backHome}
          </Button>
        }
      />
    </div>
  );
}
