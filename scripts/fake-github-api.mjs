#!/usr/bin/env node
// A stand-in for the slice of the GitHub releases API this workflow uses, for running the
// publish step locally: `node scripts/fake-github-api.mjs [port]`.
//
// It holds its state in memory and prints what it received, so a step can be run against it
// exactly as it runs on a runner. Nothing here is used by the workflow.
import http from 'node:http';
import { createHash } from 'node:crypto';

const port = Number(process.argv[2] || 8099);
const repo = process.env.GITHUB_REPOSITORY || 'WWPOL/brapmony';
const releases = [];
// Tag refs, by tag name: what a rolling tag points at. Empty here, because this stand-in
// starts with no release; the first publish creates the tag it names.
const refs = new Map();
let nextId = 100;

const server = http.createServer(async (req, res) => {
  const base = `http://127.0.0.1:${server.address().port}`;
  const url = new URL(req.url, base);
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);
  const send = (status, data, headers = {}) => {
    res.writeHead(status, { 'content-type': 'application/json', ...headers });
    res.end(data === undefined ? '' : JSON.stringify(data));
  };
  const page = (items) => {
    const n = Number(url.searchParams.get('page') || 1);
    const size = 2;
    const headers = {};
    if (n * size < items.length) {
      const next = new URL(url);
      next.searchParams.set('page', String(n + 1));
      headers.link = `<${next}>; rel="next"`;
    }
    send(200, items.slice((n - 1) * size, n * size), headers);
  };
  if (req.method === 'GET' && url.pathname === `/repos/${repo}/releases`) return page(releases);
  if (req.method === 'POST' && url.pathname === `/repos/${repo}/releases`) {
    const payload = JSON.parse(body.toString());
    const id = nextId++;
    const release = {
      id,
      tag_name: payload.tag_name,
      name: payload.name,
      body: payload.body,
      draft: !!payload.draft,
      prerelease: !!payload.prerelease,
      assets: [],
      upload_url: `${base}/repos/${repo}/releases/${id}/assets{?name,label}`,
    };
    releases.push(release);
    console.log(`created release ${id} ${release.tag_name} prerelease=${release.prerelease}`);
    return send(201, release);
  }
  let m = /^\/repos\/(.+)\/releases\/(\d+)$/.exec(url.pathname);
  if (m && req.method === 'GET') {
    const release = releases.find((r) => r.id === Number(m[2]));
    return release ? send(200, release) : send(404, { message: 'Not Found' });
  }
  m = /^\/repos\/(.+)\/releases\/(\d+)\/assets$/.exec(url.pathname);
  if (m && req.method === 'GET') {
    const release = releases.find((r) => r.id === Number(m[2]));
    return release ? page(release.assets) : send(404, { message: 'Not Found' });
  }
  if (m && req.method === 'POST') {
    const release = releases.find((r) => r.id === Number(m[2]));
    const name = url.searchParams.get('name');
    if (release.assets.some((a) => a.name === name)) {
      return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
    }
    const asset = {
      id: nextId++,
      name,
      // The publisher sends no label, because GitHub shows one in place of the file name.
      label: url.searchParams.get('label') || '',
      // A workflow's uploads are attributed to this account, and that is how the publisher
      // tells its own files from ones somebody attached by hand.
      uploader: { login: 'github-actions[bot]', type: 'Bot' },
      size: body.length,
      state: 'uploaded',
      // GitHub computes a SHA-256 over every asset it stores and reports it; the publisher
      // compares that with the file it uploaded, so the stand-in models it too.
      digest: `sha256:${createHash('sha256').update(body).digest('hex')}`,
      content: body.toString('base64'),
      browser_download_url: `${base}/${repo}/releases/download/${release.tag_name}/${name}`,
    };
    release.assets.push(asset);
    console.log(`uploaded ${name} (${body.length} bytes, label ${asset.label || '-'})`);
    return send(201, asset);
  }
  m = /^\/repos\/(.+)\/releases\/assets\/(\d+)$/.exec(url.pathname);
  if (m && req.method === 'PATCH') {
    const payload = JSON.parse(body.toString());
    for (const r of releases) {
      const a = r.assets.find((x) => x.id === Number(m[2]));
      if (a) {
        if (payload.name !== undefined) {
          a.name = payload.name;
          console.log(`renamed asset ${a.id} to ${a.name}`);
        }
        // An empty label is how the ones an earlier version labelled stop hiding their
        // names on the release page.
        if (payload.label !== undefined) {
          a.label = payload.label;
          console.log(`labelled asset ${a.id} ${JSON.stringify(a.label)}`);
        }
        return send(200, a);
      }
    }
    return send(404, { message: 'Not Found' });
  }
  if (m && req.method === 'DELETE') {
    for (const r of releases) r.assets = r.assets.filter((a) => a.id !== Number(m[2]));
    console.log(`deleted asset ${m[2]}`);
    return send(204);
  }
  // The tag refs a rolling tag is moved with. A release created from a branch has no tag
  // ref, which is a 404 here as it is on GitHub.
  m = /^\/repos\/(.+)\/git\/ref\/tags\/(.+)$/.exec(url.pathname);
  if (m && req.method === 'GET') {
    const name = decodeURIComponent(m[2]);
    return refs.has(name)
      ? send(200, { ref: `refs/tags/${name}`, object: { type: 'commit', sha: refs.get(name) } })
      : send(404, { message: 'Not Found' });
  }
  m = /^\/repos\/(.+)\/git\/refs\/tags\/(.+)$/.exec(url.pathname);
  if (m && req.method === 'PATCH') {
    const name = decodeURIComponent(m[2]);
    const payload = JSON.parse(body.toString());
    if (!refs.has(name)) return send(422, { message: 'Reference does not exist' });
    refs.set(name, payload.sha);
    console.log(`moved tag ${name} to ${payload.sha}`);
    return send(200, { ref: `refs/tags/${name}`, object: { type: 'commit', sha: payload.sha } });
  }
  if (/\/git\/refs$/.test(url.pathname) && req.method === 'POST') {
    const payload = JSON.parse(body.toString());
    const name = String(payload.ref || '').replace(/^refs\/tags\//, '');
    if (!name) return send(422, { message: 'ref is required' });
    if (refs.has(name)) return send(422, { message: 'Reference already exists' });
    refs.set(name, payload.sha);
    console.log(`created tag ${name} at ${payload.sha}`);
    return send(201, { ref: `refs/tags/${name}`, object: { type: 'commit', sha: payload.sha } });
  }
  // The branch list and the artifact list `cleanup` reads. This stand-in starts with no
  // branches and no artifacts, so a cleanup run against it lists nothing and deletes
  // nothing: the command's own behaviour is covered by scripts/release.test.mjs, and this
  // process exists to run the workflow's steps, not to model the whole API.
  if (req.method === 'GET' && url.pathname === `/repos/${repo}/branches`) return page([]);
  // The artifacts endpoint pages an object holding the page, as GitHub's does.
  if (req.method === 'GET' && url.pathname === `/repos/${repo}/actions/artifacts`) {
    return send(200, { total_count: 0, artifacts: [] });
  }
  return send(404, { message: `unhandled ${req.method} ${url.pathname}` });
});

server.listen(port, '127.0.0.1', () => {
  console.log(`fake GitHub API on http://127.0.0.1:${port} for ${repo}`);
});
