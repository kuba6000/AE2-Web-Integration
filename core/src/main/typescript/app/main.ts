import { boot } from './boot.js';
import { defaultTheme } from '../themes/default/theme.js';

boot(document.getElementById('app') as HTMLElement, defaultTheme);
