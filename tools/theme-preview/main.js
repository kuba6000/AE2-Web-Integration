import { boot } from '../runtime/app/boot.js';
import theme from '../theme/theme.js';

const style = document.createElement('link');
style.rel = 'stylesheet';
style.href = new URL('../theme/theme.css', import.meta.url).href;
document.head.append(style);
boot(document.getElementById('app'), theme, new URL('../', import.meta.url), new URL('../theme/', import.meta.url));
