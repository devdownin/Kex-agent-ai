// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';

const { Client } = await import(process.env.MCP_SDK_MODULE);
const { StreamableHTTPClientTransport } = await import(process.env.MCP_SDK_HTTP_MODULE);

const transport = new StreamableHTTPClientTransport(new URL(process.env.KEX_MCP_URL), {
  requestInit: { headers: { Authorization: `Bearer ${process.env.KEX_AGENT_API_KEY}` } },
});
const client = new Client({ name: 'kex-ci-official-client', version: '1.0.0' });

try {
  await client.connect(transport);
  const tools = await client.listTools();
  assert.equal(tools.tools.length, 7);
  assert.ok(tools.tools.some((tool) => tool.name === 'kex_status'));

  const resources = await client.listResources();
  assert.ok(resources.resources.length >= 5);

  const templates = await client.listResourceTemplates();
  assert.equal(templates.resourceTemplates.length, 4);

  const prompts = await client.listPrompts();
  assert.ok(prompts.prompts.some((prompt) => prompt.name === 'kex_supervision_triage'));

  const status = await client.callTool({ name: 'kex_status', arguments: {} });
  assert.equal(status.isError, false);
  assert.ok(status.structuredContent);

  console.log('✓ official MCP SDK completed initialize → initialized → discovery → tools/call');
} finally {
  await client.close();
}
