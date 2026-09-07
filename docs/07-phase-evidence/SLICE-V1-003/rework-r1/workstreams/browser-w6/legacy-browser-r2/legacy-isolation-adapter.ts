import { defineConfig } from '@playwright/test';
import original from './playwright.config.ts';
const servers = original.webServer;
if (!Array.isArray(servers) || servers.length !== 2) throw new Error('Original two-server contract changed');
export default defineConfig({...original,
  outputDir: 'test-results/legacy-isolated',
  reporter: [['list'], ['html', {outputFolder:'playwright-report/legacy-isolated',open:'never'}]],
  webServer: servers.map((server,index) => index===0 ? {...server,
    command: 'cd ../../backend/marketops-server && ./mvnw -B -ntp -Dmarketops.build.gitCommit='+process.env.MARKETOPS_SOURCE_HEAD_SHA+' spring-boot:test-run@browser-fixture',
    env: {...server.env, MARKETOPS_BROWSER_FIXTURE:'ISOLATED_SYNTHETIC_DATABASE', SPRING_CONFIG_IMPORT:'file:../../.env.local[.properties]'},
  } : {...server,env:{...server.env,MARKETOPS_BROWSER_ISOLATION:'ISOLATED_SYNTHETIC_DATABASE',VITE_MARKETOPS_API_BASE_URL:'http://127.0.0.1:8080',VITE_MARKETOPS_ENVIRONMENT:'local'}}),
});
