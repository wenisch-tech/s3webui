import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildBucketPolicy,
  buildIdentityPolicy,
  normalizePermissions,
  parseBucketPolicy,
  parseIdentityPolicy,
  parseS3Resource,
  validatePolicy,
} from '../../main/frontend/policy-model.mjs';

const ids = (set) => [...set].sort();

test('S3 resources are recognised only for whole buckets and their objects', () => {
  assert.deepEqual(parseS3Resource('arn:aws:s3:::photos'), { bucket: 'photos', scope: 'bucket' });
  assert.deepEqual(parseS3Resource('arn:aws:s3:::photos/*'), { bucket: 'photos', scope: 'object' });
  assert.equal(parseS3Resource('arn:aws:s3:::photos/2024/*'), null);
  assert.equal(parseS3Resource('arn:aws:s3:::*'), null);
  assert.equal(parseS3Resource('*'), null);
});

test('an identity policy survives a parse/build round trip', () => {
  const grants = new Map([
    ['photos', new Set(['list', 'read'])],
    ['backups', new Set(['list', 'read', 'write', 'delete'])],
  ]);
  const doc = buildIdentityPolicy(grants);
  assert.equal(doc.Version, '2012-10-17');
  assert.ok(doc.Statement.every((statement) => statement.Sid.startsWith('S3WebUI')));
  assert.ok(doc.Statement.every((statement) => !('Principal' in statement)));

  const parsed = parseIdentityPolicy(doc);
  assert.deepEqual(parsed.preserved, []);
  assert.deepEqual(ids(parsed.grants.get('photos')), ['list', 'read']);
  assert.deepEqual(ids(parsed.grants.get('backups')), ['delete', 'list', 'read', 'write']);
});

test('generated Sids are stable across rebuilds', () => {
  const grants = new Map([['photos', new Set(['read'])]]);
  assert.deepEqual(buildIdentityPolicy(grants), buildIdentityPolicy(grants));
});

test('hand-written grants without the companion actions are still recognised', () => {
  const parsed = parseIdentityPolicy({
    Version: '2012-10-17',
    Statement: [
      { Effect: 'Allow', Action: 's3:ListBucket', Resource: 'arn:aws:s3:::docs' },
      { Effect: 'Allow', Action: ['s3:GetObject', 's3:PutObject'], Resource: 'arn:aws:s3:::docs/*' },
    ],
  });
  assert.deepEqual(parsed.preserved, []);
  assert.deepEqual(ids(parsed.grants.get('docs')), ['list', 'read', 'write']);
});

test('s3:* over both resources maps to full control and back to s3:*', () => {
  const doc = {
    Statement: [{ Effect: 'Allow', Action: 's3:*', Resource: ['arn:aws:s3:::a', 'arn:aws:s3:::a/*'] }],
  };
  const parsed = parseIdentityPolicy(doc);
  assert.deepEqual(ids(parsed.grants.get('a')), ['full']);
  const rebuilt = buildIdentityPolicy(parsed.grants);
  assert.deepEqual(rebuilt.Statement[0].Action, ['s3:*']);
  assert.equal(normalizePermissions(['full', 'read']).size, 1);
});

test('statements the editor cannot represent are preserved verbatim', () => {
  const deny = { Effect: 'Deny', Action: 's3:DeleteObject', Resource: 'arn:aws:s3:::a/*' };
  const conditional = {
    Effect: 'Allow',
    Action: 's3:GetObject',
    Resource: 'arn:aws:s3:::a/*',
    Condition: { IpAddress: { 'aws:SourceIp': '10.0.0.0/8' } },
  };
  const foreign = { Effect: 'Allow', Action: 's3:PutBucketTagging', Resource: 'arn:aws:s3:::a' };
  const prefix = { Effect: 'Allow', Action: 's3:GetObject', Resource: 'arn:aws:s3:::a/reports/*' };
  const unused = { Effect: 'Allow', Action: 's3:GetObject', Resource: ['arn:aws:s3:::a', 'arn:aws:s3:::a/*'] };
  const orphanExtra = { Effect: 'Allow', Action: 's3:AbortMultipartUpload', Resource: 'arn:aws:s3:::a/*' };
  const input = { Version: '2012-10-17', Id: 'keep-me', Statement: [deny, conditional, foreign, prefix, unused, orphanExtra] };

  const parsed = parseIdentityPolicy(input);
  assert.equal(parsed.grants.size, 0);
  assert.deepEqual(parsed.preserved, input.Statement);

  parsed.grants.set('b', new Set(['read']));
  const rebuilt = buildIdentityPolicy(parsed.grants, parsed.preserved, parsed.rest);
  assert.equal(rebuilt.Id, 'keep-me');
  assert.deepEqual(rebuilt.Statement.slice(0, 6), input.Statement);
  assert.equal(rebuilt.Statement.length, 7);
});

test('a bucket policy round-trips with every supported principal shape', () => {
  const parsed = parseBucketPolicy(
    {
      Statement: [
        { Effect: 'Allow', Principal: '*', Action: 's3:GetObject', Resource: 'arn:aws:s3:::site/*' },
        {
          Effect: 'Allow',
          Principal: { AWS: ['arn:aws:iam::1:user/alice', 'arn:aws:iam::1:user/bob'] },
          Action: ['s3:ListBucket'],
          Resource: 'arn:aws:s3:::site',
        },
        { Effect: 'Allow', Principal: { AWS: 'arn:aws:iam::1:user/alice' }, Action: 's3:PutObject', Resource: 'arn:aws:s3:::site/*' },
        { Effect: 'Allow', Principal: { Service: 'logging.s3.amazonaws.com' }, Action: 's3:PutObject', Resource: 'arn:aws:s3:::site/*' },
      ],
    },
    'site',
  );
  assert.deepEqual(ids(parsed.grants.get('*')), ['read']);
  assert.deepEqual(ids(parsed.grants.get('arn:aws:iam::1:user/alice')), ['list', 'write']);
  assert.deepEqual(ids(parsed.grants.get('arn:aws:iam::1:user/bob')), ['list']);
  assert.equal(parsed.preserved.length, 1);

  const rebuilt = buildBucketPolicy('site', parsed.grants, parsed.preserved);
  const again = parseBucketPolicy(rebuilt, 'site');
  assert.deepEqual(
    [...again.grants].map(([key, value]) => [key, ids(value)]),
    [...parsed.grants].map(([key, value]) => [key, ids(value)]),
  );
  assert.equal(rebuilt.Statement.find((statement) => statement.Principal === '*').Action[0], 's3:GetObject');
});

test('empty grants build no document, so the caller can delete the policy', () => {
  assert.equal(buildIdentityPolicy(new Map()), null);
  assert.equal(buildBucketPolicy('a', new Map([['*', new Set()]])), null);
});

test('validation reports invalid JSON with a position', () => {
  const text = '{\n  "Statement": [\n    { "Effect": "Allow", }\n  ]\n}';
  const { errors } = validatePolicy(text, { kind: 'identity' });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /not valid/);
  assert.equal(typeof errors[0].offset, 'number');
});

test('validation reports missing statement fields and foreign buckets', () => {
  const text = JSON.stringify({
    Statement: [
      { Action: 's3:GetObject', Resource: 'arn:aws:s3:::a/*' },
      { Effect: 'Allow', Principal: '*', Action: 's3:GetObject', Resource: 'arn:aws:s3:::other/*' },
      { Effect: 'Maybe', Principal: '*' },
    ],
  });
  const messages = validatePolicy(text, { kind: 'bucket', bucket: 'a' }).errors.map((error) => error.message);
  assert.ok(messages.some((message) => /Statement 1 is missing "Effect"/.test(message)));
  assert.ok(messages.some((message) => /Statement 1 is missing "Principal"/.test(message)));
  assert.ok(messages.some((message) => /refers to bucket "other"/.test(message)));
  assert.ok(messages.some((message) => /Effect "Maybe"/.test(message)));
  assert.ok(messages.some((message) => /Statement 3 is missing "Action"/.test(message)));
  assert.ok(messages.some((message) => /Statement 3 is missing "Resource"/.test(message)));
});

test('validation accepts a well-formed policy and an empty editor', () => {
  const doc = buildIdentityPolicy(new Map([['a', new Set(['read'])]]));
  assert.deepEqual(validatePolicy(JSON.stringify(doc), { kind: 'identity' }).errors, []);
  assert.deepEqual(validatePolicy('   ', { kind: 'identity' }), { doc: null, errors: [] });
  assert.match(validatePolicy('{}', { kind: 'identity' }).errors[0].message, /no "Statement"/);
});
