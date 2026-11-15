const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

// The picker uses ordinary DOM events; no layout or browser scheduling is needed for this contract.
class Element extends EventTarget {
    children = [];
    className = '';
    style = {};
    value = '';
    hidden = false;
    classList = {
        add: (name) => {
            this.className += ` ${name}`;
        },
        toggle: (name, enabled) => {
            this.className = this.className
                .split(' ')
                .filter((value) => value !== name)
                .join(' ');
            if (enabled) this.classList.add(name);
        }
    };
    constructor(tag) {
        super();
        this.tag = tag;
    }
    append(...children) {
        this.children.push(...children);
    }
    replaceChildren(...children) {
        this.children = children;
    }
    setAttribute() {}
    focus() {}
    querySelector(selector) {
        for (const child of this.children) {
            if (!(child instanceof Element)) continue;
            if (
                selector.startsWith('.')
                    ? child.className.split(' ').includes(selector.slice(1))
                    : child.tag === selector
            )
                return child;
            const nested = child.querySelector(selector);
            if (nested) return nested;
        }
        return null;
    }
}

test('opening and searching a resource picker paints the new visible icons without a data refresh', async (t) => {
    const previous = global.document;
    global.document = Object.assign(new EventTarget(), {
        createElement: (tag) => new Element(tag),
        createDocumentFragment: () => new Element('fragment')
    });
    t.after(() => {
        global.document = previous;
    });
    const moduleUrl = (name) =>
        pathToFileURL(path.resolve(__dirname, `../../build/generated/frontend/assets/web/themes/default/${name}.js`));
    const { createResourcePicker } = await import(moduleUrl('resource-picker'));
    const { paintResourceIcon } = await import(moduleUrl('resource-icon'));
    const items = ['Iron', 'Quartz'].map((name) => ({
        itemKey: name,
        displayName: name,
        registryNamespace: 'test',
        registryPath: name.toLowerCase(),
        resourceType: 'ITEM',
        quantity: 1,
        craftable: true,
        icon: { page: 0, x: 0, y: 0, width: 16, height: 16 }
    }));
    const registered = new Set();
    const picker = createResourcePicker(() => {}, {
        craftableOnly: false,
        retry() {},
        changed() {
            const targets = picker.targets();
            registered.clear();
            for (const { element, icon } of targets) {
                registered.add(element);
                paintResourceIcon(element, icon ? { ...icon, url: 'atlas.png', pageWidth: 16, pageHeight: 16 } : null);
            }
        }
    });
    picker.render(
        {
            items,
            itemsReady: true,
            itemsError: null,
            byKey: new Map(),
            known: {},
            locale: { common: (key) => key },
            language: 'en'
        },
        null,
        false,
        'Resource'
    );
    picker.choice.dispatchEvent(new Event('click'));
    const results = () => picker.element.querySelector('ul').children.map((row) => row.children[0]);
    assert.equal(results().length, 2);
    assert.ok(
        results().every((button) => button.querySelector('.resource-icon').style.backgroundImage),
        'opening paints icons immediately'
    );
    const oldResults = results();
    const search = picker.element.querySelector('input');
    search.value = 'quartz';
    search.dispatchEvent(new Event('input'));
    assert.equal(results().length, 1);
    assert.ok(results()[0].querySelector('.resource-icon').style.backgroundImage, 'search paints replacement icons');
    assert.ok(
        oldResults.every((button) => !registered.has(button)),
        'removed results release icon registrations'
    );
    picker.close();
    assert.equal(registered.size, 1, 'closing retains only the selected resource icon');
});
