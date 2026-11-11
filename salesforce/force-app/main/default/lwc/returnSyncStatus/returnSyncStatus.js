import { LightningElement, api, wire } from 'lwc';
import { getRecord, getFieldValue, getFieldDisplayValue, notifyRecordUpdateAvailable } from 'lightning/uiRecordApi';
import STATUS_FIELD from '@salesforce/schema/Return_Request__c.Status__c';
import CREATED_DATE_FIELD from '@salesforce/schema/Return_Request__c.CreatedDate';
import LAST_MODIFIED_DATE_FIELD from '@salesforce/schema/Return_Request__c.LastModifiedDate';
import LAST_MODIFIED_BY_NAME_FIELD from '@salesforce/schema/Return_Request__c.LastModifiedBy.Name';

const FIELDS = [STATUS_FIELD, CREATED_DATE_FIELD, LAST_MODIFIED_DATE_FIELD];
// Optional so that a user who cannot read the integration user's name still gets the status, without the name.
const OPTIONAL_FIELDS = [LAST_MODIFIED_BY_NAME_FIELD];

const STATUS_NEW = 'New';
const WAIT_MS = 60 * 1000;
const TICK_MS = 1000;
// Every second tick: about 2 s plus one round trip stays under the 5 s criterion at half the server load.
const TICKS_PER_REFRESH = 2;
const BOUND_MS = 10 * 1000;

const LOADING = 'loading';
const WAITING = 'waiting';
const DECIDED = 'decided';
const NO_DECISION = 'no-decision';
const ERROR = 'error';

const TITLE = 'Return Sync';
const LOADING_TEXT = 'Loading…';
const WAITING_TEXT = 'Waiting for a decision from the sync service. Time since creation:';
const NO_DECISION_TEXT = 'No decision yet. The sync service may be down.';
const ERROR_PREFIX = 'Could not check the status:';
const LOAD_ERROR_TEXT = 'could not load the record.';
const UNKNOWN_ERROR_TEXT = 'unknown error.';
const CHECK_AGAIN_LABEL = 'Check again';

function formatDuration(ms) {
    const seconds = Number.isFinite(ms) ? Math.max(0, Math.floor(ms / 1000)) : 0;
    if (seconds < 60) {
        return `about ${seconds} s`;
    }
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) {
        return `about ${minutes} min`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
        return `about ${hours} h`;
    }
    return `about ${Math.floor(hours / 24)} d`;
}

function readErrorText(error) {
    const body = error && error.body;
    if (body && typeof body.message === 'string' && body.message) {
        return body.message;
    }
    if (Array.isArray(body) && body.length > 0 && body[0] && body[0].message) {
        return body[0].message;
    }
    if (error && typeof error.message === 'string' && error.message) {
        return error.message;
    }
    return UNKNOWN_ERROR_TEXT;
}

export default class ReturnSyncStatus extends LightningElement {
    @api recordId;

    title = TITLE;
    loadingText = LOADING_TEXT;
    noDecisionText = NO_DECISION_TEXT;
    checkAgainLabel = CHECK_AGAIN_LABEL;

    // The four inputs the shown state is derived from.
    record;
    errorText;
    buttonDeadline;
    now = Date.now();

    generation = 0;
    // Generation current when the wire last errored; a click's result must not clear an error from its own round.
    wireErrorGeneration;
    // { generation, fromButton } of the outstanding refresh; it only counts while its generation is current.
    pending;

    connected = false;
    intervalId;
    ticks = 0;
    loadBoundId;
    buttonBoundId;

    @wire(getRecord, { recordId: '$recordId', fields: FIELDS, optionalFields: OPTIONAL_FIELDS })
    wiredRecord({ data, error }) {
        if (data) {
            this.record = data;
            this.errorText = undefined;
        } else if (error) {
            this.errorText = readErrorText(error);
            this.wireErrorGeneration = this.generation;
        } else {
            // The adapter's initial empty provision is not an input.
            return;
        }
        this.clearLoadBound();
        this.reconcile();
    }

    connectedCallback() {
        this.connected = true;
        // Without a record id the wire never provisions, so a bound would report a load failure that was never attempted.
        if (this.recordId && !this.record && this.errorText === undefined) {
            this.loadBoundId = setTimeout(() => this.handleLoadBound(), BOUND_MS);
        }
        this.reconcile();
    }

    disconnectedCallback() {
        this.connected = false;
        this.stopClock();
        // Bumped even when the clock was not running, so a refresh still out cannot act on a detached component.
        this.bumpGeneration();
        this.clearLoadBound();
        this.clearButtonBound();
    }

    get status() {
        return this.record ? getFieldValue(this.record, STATUS_FIELD) : undefined;
    }

    get createdMs() {
        return Date.parse(getFieldValue(this.record, CREATED_DATE_FIELD));
    }

    get deadline() {
        return this.buttonDeadline !== undefined ? this.buttonDeadline : this.createdMs + WAIT_MS;
    }

    get state() {
        if (this.errorText !== undefined) {
            return ERROR;
        }
        if (!this.record) {
            return LOADING;
        }
        if (this.status !== STATUS_NEW) {
            return DECIDED;
        }
        return this.now < this.deadline ? WAITING : NO_DECISION;
    }

    get isError() {
        return this.state === ERROR;
    }

    get isLoading() {
        return this.state === LOADING;
    }

    get isDecided() {
        return this.state === DECIDED;
    }

    get isWaiting() {
        return this.state === WAITING;
    }

    get isNoDecision() {
        return this.state === NO_DECISION;
    }

    get showButton() {
        const state = this.state;
        return state === NO_DECISION || state === ERROR;
    }

    get buttonDisabled() {
        return this.hasCurrentPending() && this.pending.fromButton;
    }

    get waitingText() {
        return `${WAITING_TEXT} ${formatDuration(this.now - this.createdMs)}.`;
    }

    get decidedText() {
        const label = getFieldDisplayValue(this.record, STATUS_FIELD) || this.status;
        const name = getFieldValue(this.record, LAST_MODIFIED_BY_NAME_FIELD);
        const took = formatDuration(Date.parse(getFieldValue(this.record, LAST_MODIFIED_DATE_FIELD)) - this.createdMs);
        return name ? `${label} by ${name} after ${took}.` : `${label} after ${took}.`;
    }

    get errorMessage() {
        return `${ERROR_PREFIX} ${this.errorText}`;
    }

    handleCheckAgain() {
        if (this.buttonDisabled) {
            return;
        }
        const clickedAt = Date.now();
        // Supersedes a clock refresh still out instead of waiting behind it, which could hang on a phone.
        this.bumpGeneration();
        this.clearButtonBound();
        this.buttonBoundId = setTimeout(() => this.handleButtonBound(), BOUND_MS);
        this.refresh(true, clickedAt);
    }

    handleLoadBound() {
        this.loadBoundId = undefined;
        if (!this.record && this.errorText === undefined) {
            this.errorText = LOAD_ERROR_TEXT;
            this.reconcile();
        }
    }

    handleButtonBound() {
        this.buttonBoundId = undefined;
        if (this.hasCurrentPending() && this.pending.fromButton) {
            // Abandon the click's request: the button re-enables and its late result is ignored.
            this.bumpGeneration();
        }
    }

    tick() {
        this.reconcile();
        if (!this.intervalId) {
            return;
        }
        this.ticks += 1;
        // Counting ticks, not milliseconds, so a tick that fires a few ms early cannot skip a refresh.
        if (this.ticks % TICKS_PER_REFRESH === 0 && !this.hasCurrentPending()) {
            this.refresh(false);
        }
    }

    refresh(fromButton, clickedAt) {
        const generation = this.generation;
        this.pending = { generation, fromButton };
        let request;
        try {
            request = notifyRecordUpdateAvailable([{ recordId: this.recordId }]);
        } catch (error) {
            // A synchronous throw would otherwise leave the pending marker set and the button disabled until its bound.
            this.settle(generation, fromButton, clickedAt, true, error);
            return;
        }
        request.then(
            () => this.settle(generation, fromButton, clickedAt, false),
            // A rejection reason can be any falsy value, so failure is passed apart from it.
            (error) => this.settle(generation, fromButton, clickedAt, true, error)
        );
    }

    settle(generation, fromButton, clickedAt, failed, error) {
        if (generation !== this.generation) {
            return;
        }
        this.pending = undefined;
        if (fromButton) {
            this.clearButtonBound();
        }
        if (failed) {
            this.errorText = readErrorText(error);
        } else if (fromButton && this.record) {
            // LDS emits nothing for an unchanged record, so a resolved click is the only sign the read works again,
            // unless the wire itself reported a failure while the click was out.
            if (this.wireErrorGeneration !== generation) {
                this.errorText = undefined;
            }
            // A fresh emission may already have cleared the error; the click still earns its 60 s.
            if (this.errorText === undefined && this.status === STATUS_NEW) {
                this.buttonDeadline = clickedAt + WAIT_MS;
            }
        }
        this.reconcile();
    }

    reconcile() {
        this.now = Date.now();
        const waiting = this.connected && this.state === WAITING;
        if (waiting && !this.intervalId) {
            // ticks is already 0: every stop goes through stopClock, and bumpGeneration owns the reset.
            this.intervalId = setInterval(() => this.tick(), TICK_MS);
        } else if (!waiting && this.intervalId) {
            this.stopClock();
        }
    }

    stopClock() {
        if (this.intervalId) {
            clearInterval(this.intervalId);
            this.intervalId = undefined;
            this.bumpGeneration();
        }
    }

    bumpGeneration() {
        this.generation += 1;
        this.ticks = 0;
    }

    hasCurrentPending() {
        return Boolean(this.pending) && this.pending.generation === this.generation;
    }

    clearLoadBound() {
        if (this.loadBoundId) {
            clearTimeout(this.loadBoundId);
            this.loadBoundId = undefined;
        }
    }

    clearButtonBound() {
        if (this.buttonBoundId) {
            clearTimeout(this.buttonBoundId);
            this.buttonBoundId = undefined;
        }
    }
}
