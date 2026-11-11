import { createElement } from 'lwc';
import ReturnSyncStatus from 'c/returnSyncStatus';
import { getRecord, notifyRecordUpdateAvailable } from 'lightning/uiRecordApi';

const RECORD_ID = 'a00fj00000ABCDEAA1';
const T0 = Date.parse('2026-11-11T12:00:00.000Z');
const SECOND = 1000;
const STATES = ['loading', 'waiting', 'decided', 'no-decision', 'error'];

const iso = (ms) => new Date(ms).toISOString();

function newRecord(createdMs) {
    return {
        apiName: 'Return_Request__c',
        id: RECORD_ID,
        fields: {
            Status__c: { value: 'New', displayValue: 'New' },
            CreatedDate: { value: iso(createdMs), displayValue: null },
            LastModifiedDate: { value: iso(createdMs), displayValue: null },
            LastModifiedBy: {
                value: { fields: { Name: { value: 'Integration User', displayValue: null } } },
                displayValue: null
            }
        }
    };
}

function decidedRecord(createdMs, modifiedMs, options = {}) {
    const { value = 'Approved', label = 'Approved', name = 'Integration User' } = options;
    const fields = {
        Status__c: { value, displayValue: label },
        CreatedDate: { value: iso(createdMs), displayValue: null },
        LastModifiedDate: { value: iso(modifiedMs), displayValue: null }
    };
    if (name !== null) {
        fields.LastModifiedBy = {
            value: { fields: { Name: { value: name, displayValue: null } } },
            displayValue: null
        };
    }
    return { apiName: 'Return_Request__c', id: RECORD_ID, fields };
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

// One microtask for the stub's promise, one for the component's continuation and re-render.
async function flush() {
    await Promise.resolve();
    await Promise.resolve();
}

// Second by second, so a resolved refresh settles between ticks as it would in a browser.
async function advanceSeconds(n) {
    for (let i = 0; i < n; i++) {
        jest.advanceTimersByTime(SECOND);
        // eslint-disable-next-line no-await-in-loop
        await flush();
    }
}

function mount() {
    const element = createElement('c-return-sync-status', { is: ReturnSyncStatus });
    element.recordId = RECORD_ID;
    document.body.appendChild(element);
    return element;
}

const block = (element, id) => element.shadowRoot.querySelector(`[data-id="${id}"]`);
const shown = (element) => STATES.filter((id) => block(element, id));
const text = (element, id) => block(element, id).textContent.trim();
const button = (element) => element.shadowRoot.querySelector('lightning-button');
const calls = () => notifyRecordUpdateAvailable.mock.calls.length;

async function click(element) {
    button(element).click();
    await flush();
}

describe('c-return-sync-status', () => {
    beforeEach(() => {
        jest.useFakeTimers();
        jest.setSystemTime(T0);
    });

    afterEach(() => {
        while (document.body.firstChild) {
            document.body.removeChild(document.body.firstChild);
        }
        jest.clearAllMocks();
        jest.useRealTimers();
    });

    it('wires the three state fields as required and the name as optional', async () => {
        mount();
        await flush();

        const config = getRecord.getLastConfig();
        expect(config.recordId).toBe(RECORD_ID);
        expect(config.fields).toEqual([
            { objectApiName: 'Return_Request__c', fieldApiName: 'Status__c' },
            { objectApiName: 'Return_Request__c', fieldApiName: 'CreatedDate' },
            { objectApiName: 'Return_Request__c', fieldApiName: 'LastModifiedDate' }
        ]);
        expect(config.optionalFields).toEqual([
            { objectApiName: 'Return_Request__c', fieldApiName: 'LastModifiedBy.Name' }
        ]);
    });

    describe('Announcements', () => {
        const live = (element, id) => block(element, id).closest('[aria-live="polite"]');

        it('announces the decided, no-decision and error lines but not the per-second waiting text', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            expect(live(element, 'waiting')).toBeNull();

            await advanceSeconds(50);
            expect(shown(element)).toEqual(['no-decision']);
            expect(live(element, 'no-decision')).not.toBeNull();

            getRecord.error({ message: 'Server unavailable' }, 500, 'Server Error');
            await flush();
            expect(live(element, 'error')).not.toBeNull();

            getRecord.emit(decidedRecord(T0 - 60 * SECOND, T0 - 58 * SECOND));
            await flush();
            expect(live(element, 'decided')).not.toBeNull();
        });
    });

    describe('Decided on open', () => {
        it('shows the label, the name and the decision time, and never refreshes', async () => {
            const element = mount();
            getRecord.emit(decidedRecord(T0 - 120 * SECOND, T0 - 118 * SECOND));
            await flush();

            expect(shown(element)).toEqual(['decided']);
            expect(text(element, 'decided')).toContain('Approved');
            expect(text(element, 'decided')).toContain('Integration User');
            expect(text(element, 'decided')).toContain('about 2 s');
            expect(button(element)).toBeNull();

            await advanceSeconds(60);
            expect(calls()).toBe(0);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Waiting', () => {
        it('shows the elapsed time and advances it with the clock', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();

            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 10 s');
            expect(button(element)).toBeNull();

            await advanceSeconds(1);
            expect(text(element, 'waiting')).toContain('about 11 s');
            await advanceSeconds(5);
            expect(text(element, 'waiting')).toContain('about 16 s');
        });

        it('refreshes with [{ recordId }] every 2 s', async () => {
            mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            expect(calls()).toBe(0);

            await advanceSeconds(1);
            expect(calls()).toBe(0);
            await advanceSeconds(1);
            expect(calls()).toBe(1);
            await advanceSeconds(2);
            expect(calls()).toBe(2);
            await advanceSeconds(2);
            expect(calls()).toBe(3);
            for (const call of notifyRecordUpdateAvailable.mock.calls) {
                expect(call).toEqual([[{ recordId: RECORD_ID }]]);
            }
        });

        it('makes one call in 10 s when the refresh never resolves', async () => {
            notifyRecordUpdateAvailable.mockReturnValueOnce(new Promise(() => {}));
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();

            await advanceSeconds(10);
            expect(calls()).toBe(1);
            expect(shown(element)).toEqual(['waiting']);
        });
    });

    describe('Waiting to Decided', () => {
        it('renders Decided on a decided emission and stops calling', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            await advanceSeconds(2);
            expect(calls()).toBe(1);

            getRecord.emit(decidedRecord(T0 - 10 * SECOND, T0 + 1 * SECOND, { value: 'Rejected', label: 'Rejected' }));
            await flush();

            expect(shown(element)).toEqual(['decided']);
            expect(text(element, 'decided')).toContain('Rejected');
            expect(text(element, 'decided')).toContain('about 11 s');

            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Limit reached', () => {
        it('turns to No decision yet at creation + 60 s and stops calling', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 50 * SECOND));
            await flush();

            await advanceSeconds(9);
            expect(shown(element)).toEqual(['waiting']);
            expect(calls()).toBe(4);

            await advanceSeconds(1);
            expect(shown(element)).toEqual(['no-decision']);
            expect(button(element)).not.toBeNull();
            expect(button(element).disabled).toBeFalsy();
            expect(calls()).toBe(4);

            await advanceSeconds(30);
            expect(calls()).toBe(4);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Opens past the limit', () => {
        it('opens in No decision yet with zero calls', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();

            expect(shown(element)).toEqual(['no-decision']);
            expect(button(element)).not.toBeNull();

            await advanceSeconds(30);
            expect(calls()).toBe(0);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Button from No decision yet', () => {
        it('still New: renders Waiting, then No decision yet at click + 60 s and not before', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();
            await advanceSeconds(5);

            await click(element);
            expect(calls()).toBe(1);
            expect(notifyRecordUpdateAvailable).toHaveBeenLastCalledWith([{ recordId: RECORD_ID }]);
            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 1 min');

            await advanceSeconds(59);
            expect(shown(element)).toEqual(['waiting']);
            expect(calls()).toBeGreaterThan(1);

            await advanceSeconds(1);
            expect(shown(element)).toEqual(['no-decision']);
            const atLimit = calls();
            await advanceSeconds(20);
            expect(calls()).toBe(atLimit);
        });

        it('decided: renders Decided', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();

            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                getRecord.emit(decidedRecord(T0 - 90 * SECOND, T0 - 30 * SECOND));
                return Promise.resolve();
            });
            await click(element);

            expect(shown(element)).toEqual(['decided']);
            expect(text(element, 'decided')).toContain('about 1 min');
            await advanceSeconds(20);
            expect(calls()).toBe(1);
        });
    });

    describe('Before the first emission', () => {
        it('shows the Loading line, no button and no call', async () => {
            const element = mount();
            await flush();

            expect(shown(element)).toEqual(['loading']);
            expect(text(element, 'loading')).toBe('Loading…');
            expect(button(element)).toBeNull();

            await advanceSeconds(5);
            expect(shown(element)).toEqual(['loading']);
            expect(calls()).toBe(0);
        });
    });

    describe('Load bound', () => {
        it('renders Error with "could not load the record" after 10 s, then a later emission renders its state', async () => {
            const element = mount();
            await flush();

            await advanceSeconds(9);
            expect(shown(element)).toEqual(['loading']);
            await advanceSeconds(1);
            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toMatch(/could not load the record/i);
            expect(button(element)).not.toBeNull();
            expect(calls()).toBe(0);

            getRecord.emit(newRecord(T0));
            await flush();
            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 10 s');
            expect(button(element)).toBeNull();
        });
    });

    describe('No record id', () => {
        it('stays in Loading without an Error', async () => {
            const element = createElement('c-return-sync-status', { is: ReturnSyncStatus });
            document.body.appendChild(element);
            await flush();

            await advanceSeconds(10);
            await advanceSeconds(10);
            expect(shown(element)).toEqual(['loading']);
            expect(calls()).toBe(0);
        });
    });

    describe('Error on read', () => {
        it('shows the platform message, the button and zero calls', async () => {
            const element = mount();
            getRecord.error({ message: 'You do not have access to this record.' }, 403, 'Forbidden');
            await flush();

            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('You do not have access to this record.');
            expect(button(element)).not.toBeNull();

            await advanceSeconds(30);
            expect(calls()).toBe(0);
            expect(jest.getTimerCount()).toBe(0);
        });

        it('reads the message from the first entry of a body array', async () => {
            const element = mount();
            getRecord.error();
            await flush();

            expect(text(element, 'error')).toContain('The requested resource does not exist');
        });

        it('recovers through the button to Waiting with a 60 s wait from the click', async () => {
            const element = mount();
            getRecord.error({ message: 'Temporary failure' }, 503, 'Service Unavailable');
            await flush();
            await advanceSeconds(10);

            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                getRecord.emit(newRecord(T0 - 200 * SECOND));
                return Promise.resolve();
            });
            await click(element);

            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(59);
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(1);
            expect(shown(element)).toEqual(['no-decision']);
        });

        it('recovers through the button to Decided', async () => {
            const element = mount();
            getRecord.error({ message: 'Temporary failure' }, 503, 'Service Unavailable');
            await flush();

            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                getRecord.emit(decidedRecord(T0 - 20 * SECOND, T0 - 18 * SECOND));
                return Promise.resolve();
            });
            await click(element);

            expect(shown(element)).toEqual(['decided']);
            expect(button(element)).toBeNull();
        });
    });

    describe('Button after a read error, no emission', () => {
        it('keeps Error and the button when the refresh resolves without data', async () => {
            const element = mount();
            getRecord.error({ message: 'Temporary failure' }, 503, 'Service Unavailable');
            await flush();

            await click(element);

            expect(calls()).toBe(1);
            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Temporary failure');
            expect(button(element)).not.toBeNull();
            expect(button(element).disabled).toBeFalsy();
            await advanceSeconds(20);
            expect(calls()).toBe(1);
        });
    });

    describe('Button when the wire errors during its refresh', () => {
        it('keeps Error and the button although the refresh resolves', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            getRecord.error({ message: 'Server unavailable' }, 500, 'Server Error');
            await flush();
            expect(shown(element)).toEqual(['error']);

            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                getRecord.error({ message: 'Still unavailable' }, 500, 'Server Error');
                return Promise.resolve();
            });
            await click(element);

            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Still unavailable');
            expect(button(element)).not.toBeNull();
            expect(button(element).disabled).toBeFalsy();
            await advanceSeconds(20);
            expect(calls()).toBe(1);
        });
    });

    describe('Refresh rejects while waiting', () => {
        it('shows Error with the message and stops calling', async () => {
            notifyRecordUpdateAvailable.mockRejectedValueOnce({ body: { message: 'Network failure' } });
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();

            await advanceSeconds(2);
            expect(calls()).toBe(1);
            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Network failure');
            expect(button(element)).not.toBeNull();

            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });

        it('falls back to the error message when there is no body', async () => {
            notifyRecordUpdateAvailable.mockRejectedValueOnce(new Error('Failed to fetch'));
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();

            await advanceSeconds(2);
            expect(text(element, 'error')).toContain('Failed to fetch');
        });
    });

    describe('Button whose own refresh rejects', () => {
        it('shows Error with the message and re-enables the button', async () => {
            notifyRecordUpdateAvailable.mockRejectedValueOnce({ body: { message: 'Network failure' } });
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();
            expect(shown(element)).toEqual(['no-decision']);

            await click(element);

            expect(calls()).toBe(1);
            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Network failure');
            expect(button(element)).not.toBeNull();
            expect(button(element).disabled).toBeFalsy();
            await advanceSeconds(20);
            expect(calls()).toBe(1);
        });
    });

    describe('Refresh throws synchronously', () => {
        it('shows Error and re-enables the button after a click', async () => {
            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                throw new Error('Adapter unavailable');
            });
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();

            await click(element);

            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Adapter unavailable');
            expect(button(element).disabled).toBeFalsy();
        });

        it('shows Error from the clock and stops calling', async () => {
            notifyRecordUpdateAvailable.mockImplementationOnce(() => {
                throw new Error('Adapter unavailable');
            });
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();

            await advanceSeconds(2);
            expect(shown(element)).toEqual(['error']);
            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Button after a rejected refresh', () => {
        it('renders Waiting with the deadline at click + 60 s', async () => {
            notifyRecordUpdateAvailable.mockRejectedValueOnce({ body: { message: 'Network failure' } });
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            await advanceSeconds(2);
            expect(shown(element)).toEqual(['error']);

            await click(element);
            expect(shown(element)).toEqual(['waiting']);

            // The creation deadline (T0 + 50 s) has passed by here; the click's deadline is T0 + 62 s.
            await advanceSeconds(53);
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(6);
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(1);
            expect(shown(element)).toEqual(['no-decision']);
        });
    });

    describe('Button after a wire error mid-wait', () => {
        it('renders Waiting with a new 60 s, because the error kept the last data', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            await advanceSeconds(3);
            expect(calls()).toBe(1);

            getRecord.error({ message: 'Server unavailable' }, 500, 'Server Error');
            await flush();
            expect(shown(element)).toEqual(['error']);
            expect(text(element, 'error')).toContain('Server unavailable');
            await advanceSeconds(5);
            expect(calls()).toBe(1);

            await click(element);
            expect(calls()).toBe(2);
            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 18 s');

            // Click at T0 + 8 s: Waiting until T0 + 68 s, past the creation deadline of T0 + 50 s.
            await advanceSeconds(59);
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(1);
            expect(shown(element)).toEqual(['no-decision']);
        });
    });

    describe('Emission after an error', () => {
        it('before the deadline: renders Waiting with the clock running and refreshes resuming', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            await advanceSeconds(3);
            expect(calls()).toBe(1);

            getRecord.error({ message: 'Server unavailable' }, 500, 'Server Error');
            await flush();
            await advanceSeconds(5);
            expect(shown(element)).toEqual(['error']);
            expect(calls()).toBe(1);

            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 18 s');

            await advanceSeconds(1);
            expect(text(element, 'waiting')).toContain('about 19 s');
            await advanceSeconds(1);
            expect(calls()).toBe(2);
        });

        it('past the deadline: renders No decision yet with the button', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 50 * SECOND));
            await flush();
            await advanceSeconds(3);

            getRecord.error({ message: 'Server unavailable' }, 500, 'Server Error');
            await flush();
            await advanceSeconds(10);
            const before = calls();

            getRecord.emit(newRecord(T0 - 50 * SECOND));
            await flush();
            expect(shown(element)).toEqual(['no-decision']);
            expect(button(element)).not.toBeNull();
            await advanceSeconds(20);
            expect(calls()).toBe(before);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Refresh settles after the deadline', () => {
        async function hangPastDeadline() {
            const pending = deferred();
            notifyRecordUpdateAvailable.mockReturnValueOnce(pending.promise);
            const element = mount();
            getRecord.emit(newRecord(T0 - 55 * SECOND));
            await flush();

            await advanceSeconds(2);
            expect(calls()).toBe(1);
            await advanceSeconds(3);
            expect(shown(element)).toEqual(['no-decision']);
            return { element, pending };
        }

        it('resolve run: stays in No decision yet with no further calls', async () => {
            const { element, pending } = await hangPastDeadline();

            pending.resolve();
            await flush();
            expect(shown(element)).toEqual(['no-decision']);
            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });

        it('reject run: stays in No decision yet with no further calls', async () => {
            const { element, pending } = await hangPastDeadline();

            pending.reject({ body: { message: 'Network failure' } });
            await flush();
            expect(shown(element)).toEqual(['no-decision']);
            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Stale refresh after a click', () => {
        it('ignores the old refresh rejecting after the click put the page back in Waiting', async () => {
            const stale = deferred();
            notifyRecordUpdateAvailable.mockReturnValueOnce(stale.promise);
            const element = mount();
            getRecord.emit(newRecord(T0 - 55 * SECOND));
            await flush();
            await advanceSeconds(6);
            expect(shown(element)).toEqual(['no-decision']);
            expect(calls()).toBe(1);

            await click(element);
            expect(calls()).toBe(2);
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(2);
            expect(calls()).toBe(3);

            stale.reject({ body: { message: 'Network failure' } });
            await flush();
            expect(shown(element)).toEqual(['waiting']);
            await advanceSeconds(2);
            expect(calls()).toBe(4);
            expect(shown(element)).toEqual(['waiting']);
        });
    });

    describe('Button while refreshing', () => {
        it('is disabled while its refresh is pending, and a second click makes no call', async () => {
            notifyRecordUpdateAvailable.mockReturnValueOnce(new Promise(() => {}));
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();

            await click(element);
            expect(calls()).toBe(1);
            expect(button(element).disabled).toBe(true);

            await click(element);
            expect(calls()).toBe(1);
        });
    });

    describe('Button bound', () => {
        it('re-enables the button after 10 s with the state unchanged, and ignores the late result', async () => {
            const pending = deferred();
            notifyRecordUpdateAvailable.mockReturnValueOnce(pending.promise);
            const element = mount();
            getRecord.emit(newRecord(T0 - 90 * SECOND));
            await flush();

            await click(element);
            expect(button(element).disabled).toBe(true);
            await advanceSeconds(9);
            expect(button(element).disabled).toBe(true);
            await advanceSeconds(1);
            expect(button(element).disabled).toBeFalsy();
            expect(shown(element)).toEqual(['no-decision']);

            pending.resolve();
            await flush();
            expect(shown(element)).toEqual(['no-decision']);
            expect(button(element).disabled).toBeFalsy();
            await advanceSeconds(20);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);
        });
    });

    describe('Disconnect and reconnect while waiting', () => {
        it('makes no call after the disconnect, then resumes the clock and refreshes', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 - 10 * SECOND));
            await flush();
            await advanceSeconds(2);
            expect(calls()).toBe(1);

            document.body.removeChild(element);
            expect(jest.getTimerCount()).toBe(0);
            await advanceSeconds(10);
            expect(calls()).toBe(1);
            expect(jest.getTimerCount()).toBe(0);

            document.body.appendChild(element);
            await flush();
            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 22 s');
            await advanceSeconds(1);
            expect(text(element, 'waiting')).toContain('about 23 s');
            await advanceSeconds(1);
            expect(calls()).toBe(2);
        });
    });

    describe('Formatting', () => {
        it.each([
            [0, 'about 0 s'],
            [45 * SECOND, 'about 45 s'],
            [59 * SECOND + 999, 'about 59 s'],
            [60 * SECOND, 'about 1 min'],
            [59 * 60 * SECOND, 'about 59 min'],
            [2 * 3600 * SECOND + 5 * 60 * SECOND, 'about 2 h'],
            [3 * 24 * 3600 * SECOND, 'about 3 d'],
            [-5 * SECOND, 'about 0 s']
        ])('a decision time of %i ms reads "%s"', async (duration, expected) => {
            const element = mount();
            getRecord.emit(decidedRecord(T0 - 10 * 24 * 3600 * SECOND, T0 - 10 * 24 * 3600 * SECOND + duration));
            await flush();

            expect(text(element, 'decided')).toContain(expected);
        });

        it('shows a negative elapsed time, from a client clock behind the server, as 0 s', async () => {
            const element = mount();
            getRecord.emit(newRecord(T0 + 30 * SECOND));
            await flush();

            expect(shown(element)).toEqual(['waiting']);
            expect(text(element, 'waiting')).toContain('about 0 s');
        });

        it('shows the picklist label, not the API value', async () => {
            const element = mount();
            getRecord.emit(
                decidedRecord(T0 - 10 * SECOND, T0 - 7 * SECOND, { value: 'Manual_Review', label: 'Manual Review' })
            );
            await flush();

            expect(text(element, 'decided')).toContain('Manual Review');
            expect(text(element, 'decided')).not.toContain('Manual_Review');
        });
    });

    describe('No name', () => {
        it('renders Decided without the name when LastModifiedBy.Name is absent', async () => {
            const element = mount();
            getRecord.emit(decidedRecord(T0 - 10 * SECOND, T0 - 7 * SECOND, { value: 'Rejected', label: 'Rejected', name: null }));
            await flush();

            expect(shown(element)).toEqual(['decided']);
            expect(text(element, 'decided')).toBe('Rejected after about 3 s.');
        });
    });
});
