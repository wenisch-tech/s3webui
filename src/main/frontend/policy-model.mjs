// Translates between IAM-style policy documents and the simple "who may do what on which bucket"
// grants the visual permission editor shows. Pure functions only, so the round-trip rules can be
// unit-tested without a browser.
//
// A statement is shown as a grant only when it can be regenerated without changing its meaning.
// Everything else (Deny, Condition, wildcard resources, unknown actions, ...) is carried through
// verbatim as a "preserved" statement, so a visual save never drops a hand-written rule.

export const POLICY_VERSION = '2012-10-17';
export const PUBLIC_PRINCIPAL = '*';
const S3_ARN = 'arn:aws:s3:::';
const ALL_ACTIONS = 's3:*';

/**
 * The permission chips, in display order. `core` actions identify the permission when a policy
 * is parsed; `extra` actions are emitted alongside them because S3 clients need them in practice
 * (a multipart upload cannot be aborted without AbortMultipartUpload, for example).
 */
export const PERMISSIONS = [
  {
    id: 'list',
    label: 'List',
    icon: 'list-bullet',
    hint: 'See which files and folders exist',
    scope: 'bucket',
    core: ['s3:ListBucket'],
    extra: ['s3:GetBucketLocation'],
  },
  {
    id: 'read',
    label: 'Read',
    icon: 'eye',
    hint: 'Download and open files',
    scope: 'object',
    core: ['s3:GetObject'],
    extra: [],
  },
  {
    id: 'write',
    label: 'Write',
    icon: 'arrow-up-tray',
    hint: 'Upload new files and overwrite existing ones',
    scope: 'object',
    core: ['s3:PutObject'],
    extra: ['s3:AbortMultipartUpload', 's3:ListMultipartUploadParts'],
  },
  {
    id: 'delete',
    label: 'Delete',
    icon: 'trash',
    hint: 'Remove files',
    scope: 'object',
    core: ['s3:DeleteObject'],
    extra: [],
    danger: true,
  },
];

/** Grants every S3 action (s3:*). Implies, and supersedes, the four basic permissions. */
export const FULL_PERMISSION = {
  id: 'full',
  label: 'Full control',
  icon: 'shield-check',
  hint: 'Every S3 action, including changing bucket settings and policies',
  danger: true,
};

export const BASIC_PERMISSION_IDS = PERMISSIONS.map((permission) => permission.id);

export const PRESETS = [
  { id: 'read', label: 'Read only', permissions: ['list', 'read'] },
  { id: 'readwrite', label: 'Read & write', permissions: ['list', 'read', 'write', 'delete'] },
  { id: 'full', label: 'Full control', permissions: ['full'] },
];

const ACTION_TO_PERMISSION = new Map();
for (const permission of PERMISSIONS) {
  for (const action of permission.core) ACTION_TO_PERMISSION.set(action.toLowerCase(), { permission, core: true });
  for (const action of permission.extra) ACTION_TO_PERMISSION.set(action.toLowerCase(), { permission, core: false });
}

// ── Small helpers ──────────────────────────────────────────────────────────

const asArray = (value) => (value === undefined || value === null ? [] : Array.isArray(value) ? value : [value]);
const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

export const bucketArn = (bucket) => `${S3_ARN}${bucket}`;
export const objectArn = (bucket) => `${S3_ARN}${bucket}/*`;

/** {bucket, scope} for `arn:aws:s3:::bucket` / `arn:aws:s3:::bucket/*`; null for anything else. */
export function parseS3Resource(resource) {
  if (typeof resource !== 'string' || !resource.startsWith(S3_ARN)) return null;
  const rest = resource.slice(S3_ARN.length);
  if (!rest || rest.includes('*') && !rest.endsWith('/*')) return null;
  if (rest.endsWith('/*')) {
    const bucket = rest.slice(0, -2);
    return bucket && !/[/*?]/.test(bucket) ? { bucket, scope: 'object' } : null;
  }
  return /[/*?]/.test(rest) ? null : { bucket: rest, scope: 'bucket' };
}

/** Principals named by a statement, or null when it uses a shape the editor cannot represent. */
export function parsePrincipals(principal) {
  if (principal === '*') return [PUBLIC_PRINCIPAL];
  if (!isPlainObject(principal)) return null;
  const keys = Object.keys(principal);
  if (keys.length !== 1 || keys[0] !== 'AWS') return null;
  const values = asArray(principal.AWS);
  if (!values.length || values.some((value) => typeof value !== 'string' || !value)) return null;
  return values.map((value) => (value === '*' ? PUBLIC_PRINCIPAL : value));
}

/** A short deterministic tag so generated Sids stay stable across saves. */
export function stableHash(text) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

/** Normalises a permission set: "full" swallows everything else; unknown ids are dropped. */
export function normalizePermissions(permissions) {
  const set = new Set(permissions);
  if (set.has('full')) return new Set(['full']);
  return new Set(BASIC_PERMISSION_IDS.filter((id) => set.has(id)));
}

function addGrant(grants, subject, permissions) {
  const current = grants.get(subject) ?? new Set();
  for (const permission of permissions) current.add(permission);
  grants.set(subject, normalizePermissions(current));
}

// ── Statement recognition ──────────────────────────────────────────────────

/**
 * Decides whether a statement is a plain S3 grant. Returns `{buckets, permissions}` when it is,
 * or null when it must be preserved. Strict on purpose: every resource must be used by some
 * granted permission and every permission must cover every bucket it names, so regenerating the
 * statement from the grant cannot widen or narrow it.
 */
function recogniseGrant(statement) {
  if (!isPlainObject(statement) || statement.Effect !== 'Allow') return null;
  const known = new Set(['Sid', 'Effect', 'Action', 'Resource', 'Principal']);
  if (Object.keys(statement).some((key) => !known.has(key))) return null;

  const actions = asArray(statement.Action);
  const resources = asArray(statement.Resource).map(parseS3Resource);
  if (!actions.length || !resources.length || resources.some((resource) => !resource)) return null;
  if (actions.some((action) => typeof action !== 'string')) return null;

  const buckets = [...new Set(resources.map((resource) => resource.bucket))];
  const scopesFor = (bucket) =>
    new Set(resources.filter((resource) => resource.bucket === bucket).map((resource) => resource.scope));

  const lowered = actions.map((action) => action.toLowerCase());
  if (lowered.includes(ALL_ACTIONS)) {
    if (lowered.length !== 1) return null;
    const complete = buckets.every((bucket) => scopesFor(bucket).size === 2);
    return complete ? { buckets, permissions: new Set(['full']) } : null;
  }

  const granted = new Set();
  const mentioned = new Set();
  for (const action of lowered) {
    const match = ACTION_TO_PERMISSION.get(action);
    if (!match) return null;
    mentioned.add(match.permission);
    if (match.core) granted.add(match.permission);
  }
  // An "extra" action without its core one (AbortMultipartUpload alone) is not a chip state.
  if ([...mentioned].some((permission) => !granted.has(permission))) return null;

  const neededScopes = new Set([...granted].map((permission) => permission.scope));
  for (const bucket of buckets) {
    const scopes = scopesFor(bucket);
    if (scopes.size !== neededScopes.size || [...neededScopes].some((scope) => !scopes.has(scope))) return null;
  }
  return { buckets, permissions: new Set([...granted].map((permission) => permission.id)) };
}

function splitDocument(doc) {
  const { Statement, Version, ...rest } = isPlainObject(doc) ? doc : {};
  return { statements: asArray(Statement), version: Version, rest };
}

/**
 * Parses an identity (user / group) policy: grants are keyed by bucket name.
 * @returns {{grants: Map<string, Set<string>>, preserved: object[], rest: object}}
 */
export function parseIdentityPolicy(doc) {
  const { statements, rest } = splitDocument(doc);
  const grants = new Map();
  const preserved = [];
  for (const statement of statements) {
    const grant = 'Principal' in Object(statement) ? null : recogniseGrant(statement);
    if (!grant) {
      preserved.push(statement);
      continue;
    }
    for (const bucket of grant.buckets) addGrant(grants, bucket, grant.permissions);
  }
  return { grants, preserved, rest };
}

/**
 * Parses a bucket policy: grants are keyed by principal ARN ("*" for everyone). Statements that
 * mention any other bucket are preserved rather than shown.
 * @returns {{grants: Map<string, Set<string>>, preserved: object[], rest: object}}
 */
export function parseBucketPolicy(doc, bucket) {
  const { statements, rest } = splitDocument(doc);
  const grants = new Map();
  const preserved = [];
  for (const statement of statements) {
    const principals = isPlainObject(statement) ? parsePrincipals(statement.Principal) : null;
    const grant = principals ? recogniseGrant(statement) : null;
    if (!grant || grant.buckets.length !== 1 || grant.buckets[0] !== bucket) {
      preserved.push(statement);
      continue;
    }
    for (const principal of principals) addGrant(grants, principal, grant.permissions);
  }
  return { grants, preserved, rest };
}

// ── Document generation ────────────────────────────────────────────────────

function statementsFor(bucket, permissions, sidSeed, principal) {
  const normalized = normalizePermissions(permissions);
  if (!normalized.size) return [];
  const tag = stableHash(sidSeed);
  const withPrincipal = (statement) =>
    principal === undefined
      ? statement
      : { Sid: statement.Sid, Effect: statement.Effect, Principal: principalValue(principal), Action: statement.Action, Resource: statement.Resource };

  if (normalized.has('full')) {
    return [withPrincipal({ Sid: `S3WebUIFull${tag}`, Effect: 'Allow', Action: [ALL_ACTIONS], Resource: [bucketArn(bucket), objectArn(bucket)] })];
  }
  const chosen = PERMISSIONS.filter((permission) => normalized.has(permission.id));
  const result = [];
  for (const scope of ['bucket', 'object']) {
    const actions = chosen
      .filter((permission) => permission.scope === scope)
      .flatMap((permission) => [...permission.core, ...permission.extra]);
    if (!actions.length) continue;
    result.push(
      withPrincipal({
        Sid: `S3WebUI${scope === 'bucket' ? 'Bucket' : 'Objects'}${tag}`,
        Effect: 'Allow',
        Action: actions,
        Resource: [scope === 'bucket' ? bucketArn(bucket) : objectArn(bucket)],
      }),
    );
  }
  return result;
}

function principalValue(principal) {
  return principal === PUBLIC_PRINCIPAL ? '*' : { AWS: [principal] };
}

function assemble(rest, preserved, generated) {
  const statements = [...preserved, ...generated];
  if (!statements.length) return null;
  return { Version: POLICY_VERSION, ...rest, Statement: statements };
}

/** Builds an identity policy, or null when it would grant nothing at all. */
export function buildIdentityPolicy(grants, preserved = [], rest = {}) {
  const generated = [...grants].flatMap(([bucket, permissions]) => statementsFor(bucket, permissions, `bucket:${bucket}`));
  return assemble(rest, preserved, generated);
}

/** Builds a bucket policy, or null when it would contain no statements (i.e. delete it). */
export function buildBucketPolicy(bucket, grants, preserved = [], rest = {}) {
  const generated = [...grants].flatMap(([principal, permissions]) =>
    statementsFor(bucket, permissions, `principal:${principal}`, principal),
  );
  return assemble(rest, preserved, generated);
}

// ── Validation ─────────────────────────────────────────────────────────────

/** Converts a character offset into a 1-based {line, column}. */
export function offsetToLineColumn(text, offset) {
  const before = text.slice(0, Math.max(0, Math.min(offset, text.length)));
  const lines = before.split('\n');
  return { line: lines.length, column: lines.at(-1).length + 1 };
}

function lineColumnToOffset(text, line, column) {
  const lines = text.split('\n');
  let offset = 0;
  for (let i = 0; i < line - 1 && i < lines.length; i += 1) offset += lines[i].length + 1;
  return Math.min(text.length, offset + Math.max(0, column - 1));
}

/** Best-effort location of a JSON.parse failure across V8 / SpiderMonkey / JSC message formats. */
export function jsonErrorOffset(text, error) {
  const message = String(error?.message ?? '');
  const position = message.match(/position (\d+)/i);
  if (position) return Number(position[1]);
  const lineColumn = message.match(/line (\d+) column (\d+)/i);
  if (lineColumn) return lineColumnToOffset(text, Number(lineColumn[1]), Number(lineColumn[2]));
  return null;
}

function friendlyJsonMessage(error) {
  return String(error?.message ?? 'Invalid JSON')
    .replace(/^JSON\.parse: /, '')
    .replace(/\s*in JSON at position \d+.*$/i, '')
    .replace(/\s*\(line \d+ column \d+\)$/i, '');
}

/**
 * Validates policy text. `errors` hold `{message, offset?}`; offset (when known) points into the
 * text so the JSON editor can place the cursor there.
 * @param {string} text
 * @param {{kind: 'identity'|'bucket', bucket?: string}} options
 * @returns {{doc: object|null, errors: {message: string, offset?: number}[]}}
 */
export function validatePolicy(text, { kind, bucket } = {}) {
  if (!String(text ?? '').trim()) return { doc: null, errors: [] };
  let doc;
  try {
    doc = JSON.parse(text);
  } catch (error) {
    const offset = jsonErrorOffset(text, error);
    const where = offset === null ? '' : (({ line, column }) => ` (line ${line}, column ${column})`)(offsetToLineColumn(text, offset));
    return { doc: null, errors: [{ message: `The JSON is not valid: ${friendlyJsonMessage(error)}${where}.`, offset: offset ?? undefined }] };
  }

  const errors = [];
  const locate = (needle, from = 0) => {
    const index = text.indexOf(needle, from);
    return index < 0 ? undefined : index;
  };
  if (!isPlainObject(doc)) {
    return { doc: null, errors: [{ message: 'A policy must be a JSON object such as {"Version": …, "Statement": […]}.', offset: 0 }] };
  }
  if (!('Statement' in doc)) {
    errors.push({ message: 'The policy has no "Statement" list.', offset: 0 });
    return { doc, errors };
  }
  if (!Array.isArray(doc.Statement) && !isPlainObject(doc.Statement)) {
    errors.push({ message: '"Statement" must be a list of statements.', offset: locate('"Statement"') });
    return { doc, errors };
  }

  // Offsets for statements are approximate: the n-th occurrence of an "Effect"/"Sid" key, falling
  // back to the Statement key itself, which is close enough to put the user in the right place.
  const statementOffsets = [];
  let cursor = locate('"Statement"') ?? 0;
  asArray(doc.Statement).forEach(() => {
    const next = locate('{', cursor + 1);
    statementOffsets.push(next);
    cursor = next ?? cursor;
  });

  asArray(doc.Statement).forEach((statement, index) => {
    const label = `Statement ${index + 1}${isPlainObject(statement) && statement.Sid ? ` ("${statement.Sid}")` : ''}`;
    const offset = statementOffsets[index];
    if (!isPlainObject(statement)) {
      errors.push({ message: `${label} must be an object.`, offset });
      return;
    }
    if (!('Effect' in statement)) errors.push({ message: `${label} is missing "Effect" (Allow or Deny).`, offset });
    else if (statement.Effect !== 'Allow' && statement.Effect !== 'Deny')
      errors.push({ message: `${label} has Effect "${statement.Effect}"; use "Allow" or "Deny".`, offset });
    if (!('Action' in statement) && !('NotAction' in statement)) errors.push({ message: `${label} is missing "Action".`, offset });
    if (!('Resource' in statement) && !('NotResource' in statement)) errors.push({ message: `${label} is missing "Resource".`, offset });
    if (kind === 'bucket') {
      if (!('Principal' in statement) && !('NotPrincipal' in statement))
        errors.push({ message: `${label} is missing "Principal" — a bucket policy must say who it applies to.`, offset });
      if (bucket) {
        const other = asArray(statement.Resource)
          .map(parseS3Resource)
          .find((resource) => resource && resource.bucket !== bucket);
        if (other) errors.push({ message: `${label} refers to bucket "${other.bucket}", but this is the policy of "${bucket}".`, offset });
      }
    }
  });
  return { doc, errors };
}

/** Pretty-prints a policy object (or "" for none) the way the JSON editor shows it. */
export function formatPolicy(doc) {
  return doc ? JSON.stringify(doc, null, 2) : '';
}
