import type { Translator } from '../../app/i18n.js';
import type { Route } from '../../app/router.js';
import { project } from '../../app/project.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

function link(text: string, url: string) {
    const node = element('a', text);
    node.href = url;
    node.target = '_blank';
    node.rel = 'noopener noreferrer';
    return node;
}

export function createAboutView(root: HTMLElement, modVersion: string | null) {
    const view = element('section');
    view.className = 'about-view';
    view.hidden = true;
    const title = element('h2');
    const mod = element('section');
    const modTitle = element('h3');
    const version = element('p');
    const author = element('p');
    const license = element('p');
    const source = link('', project.url);
    const issues = link('', project.issues);
    const links = element('div');
    links.className = 'about-links';
    links.append(source, issues);
    mod.append(modTitle, element('strong', project.name), version, author, license, links);

    const theme = element('section');
    const themeTitle = element('h3');
    const themeName = element('strong');
    const themeAuthor = element('p');
    const themeLicense = element('p');
    const themeSource = link('', project.url);
    theme.append(themeTitle, themeName, themeAuthor, themeLicense, themeSource);

    const resources = element('section');
    const resourcesTitle = element('h3');
    const table = element('table');
    const head = element('thead');
    const headings = element('tr');
    const resourceLabel = element('th');
    const licenseLabel = element('th');
    resourceLabel.scope = 'col';
    licenseLabel.scope = 'col';
    headings.append(resourceLabel, licenseLabel);
    head.append(headings);
    const body = element('tbody');
    for (const resource of [
        { name: 'Monocraft', url: 'https://github.com/IdreesInc/Monocraft', license: 'SIL Open Font License 1.1' },
        {
            name: 'HackerNoon Pixel Icon Library',
            url: 'https://github.com/hackernoon/pixel-icon-library',
            license: 'MIT'
        }
    ]) {
        const row = element('tr');
        const name = element('td');
        name.append(link(resource.name, resource.url));
        row.append(name, element('td', resource.license));
        body.append(row);
    }
    table.append(head, body);
    resources.append(resourcesTitle, table);
    view.append(title, mod, theme, resources);
    root.append(view);

    return {
        render(route: Route, locale: Translator) {
            view.hidden = route.view !== 'about';
            if (view.hidden) return;
            title.textContent = locale.common('about');
            modTitle.textContent = locale.common('aboutMod');
            version.textContent = locale.common('aboutVersion', {
                version: modVersion ?? locale.common('versionUnknown')
            });
            author.textContent = themeAuthor.textContent = locale.common('aboutAuthor', { author: project.author });
            license.textContent = themeLicense.textContent = locale.common('aboutLicense', {
                license: project.license
            });
            source.textContent = themeSource.textContent = locale.common('projectSource');
            issues.textContent = locale.common('reportIssue');
            themeTitle.textContent = locale.common('activeTheme');
            themeName.textContent = locale.t('defaultTheme');
            resourcesTitle.textContent = locale.common('includedResources');
            resourceLabel.textContent = locale.common('resourceName');
            licenseLabel.textContent = locale.common('license');
        }
    };
}
