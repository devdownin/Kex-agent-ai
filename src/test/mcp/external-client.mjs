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

  const safeTools = ['kex_status', 'kex_overview', 'kex_alerts', 'kex_incidents', 'kex_pending_decisions'];
  for (const name of safeTools) {
    const result = await client.callTool({ name, arguments: {} });
    if (result.isError) {
      // A real CI instance may have no supervision snapshot yet. The SDK must still decode
      // the MCP tool result cleanly instead of failing output-schema/transport validation.
      assert.ok(result.content?.length, name);
    } else {
      assert.ok(result.structuredContent, name);
    }
  }

  for (const uri of [
    'kex://supervision/processes/order-integration',
    'kex://kafka/topics/orders',
    'kex://kafka/topics/orders/consumer-groups',
    'kex://kafka/topics/orders/lag',
  ]) {
    try {
      const result = await client.readResource({ uri });
      assert.ok(result.contents?.length, uri);
    } catch (error) {
      // The transport/template contract is what this probe verifies. Domain data may be unavailable in CI.
      assert.ok(!String(error).includes('Method not found'), uri);
    }
  }

  console.log('✓ official MCP SDK completed discovery, typed tool calls and all resource templates');
} finally {
  await client.close();
}
