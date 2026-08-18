'use client';

import { Fragment, useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Checkbox, Drawer, Group, Modal, Paper, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconChevronRight, IconEdit, IconLink, IconPlus, IconRefresh, IconShieldCheck, IconTrash, IconX } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPatch, apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { MaskingPolicy, MaskingRule } from '@/lib/types';
import { EmptyPanel, InlineDanger, isLookupOptionsFunction, LookupOptionsBuilder, MaskingHeader, ParamControl } from './components';
import { useDataSources, useDiscoveryFindings, useMaskingFunctions, useMaskingLookupReferences, useMaskingScripts, useMaskingValueLists, usePolicies, usePolicyRules } from './hooks';
import type { DiscoveryFinding, PolicyDraft, RuleDraft } from './types';
import {
  defaultMaskParamsForMap,
  formatDate,
  numberOrNull,
  ruleSignature,
  safeInputValue,
  technicalInputProps
} from './utils';

const emptyPolicyDraft: PolicyDraft = {
  name: '',
  description: '',
  dataSourceId: '',
  schemaName: ''
};

const emptyRuleDraft: RuleDraft = {
  schemaName: '',
  tableName: '',
  columnName: '',
  functionName: '',
  param1: '',
  param2: ''
};

type StructuredLeafRule = {
  selector: string;
  function: string;
  salt?: string | null;
  param1?: string | null;
  param2?: string | null;
};

type StructuredRuleConfig = {
  format: string;
  rules: StructuredLeafRule[];
};

type StructuredRuleEditor = {
  parent: MaskingRule;
  config: StructuredRuleConfig;
  index: number;
  functionName: string;
  param1: string;
  param2: string;
};

export function MaskingPoliciesPage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('policy.manage');
  const policiesQuery = usePolicies();
  const functionsQuery = useMaskingFunctions();
  const scriptsQuery = useMaskingScripts();
  const valueListsQuery = useMaskingValueLists();
  const lookupReferencesQuery = useMaskingLookupReferences();
  const dataSourcesQuery = useDataSources();
  const policies = useMemo(() => policiesQuery.data || [], [policiesQuery.data]);
  const functions = useMemo(() => functionsQuery.data || [], [functionsQuery.data]);
  const scripts = useMemo(() => scriptsQuery.data || [], [scriptsQuery.data]);
  const valueLists = useMemo(() => valueListsQuery.data || [], [valueListsQuery.data]);
  const lookupReferences = useMemo(() => lookupReferencesQuery.data || [], [lookupReferencesQuery.data]);
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const [policyDraft, setPolicyDraft] = useState<PolicyDraft>(emptyPolicyDraft);
  const [selectedPolicyId, setSelectedPolicyId] = useState<number | null>(null);
  const [createOpened, setCreateOpened] = useState(false);
  const [editorOpened, setEditorOpened] = useState(false);
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(emptyRuleDraft);
  const [policySearch, setPolicySearch] = useState('');
  const [mapDataSourceId, setMapDataSourceId] = useState('');
  const [mapSchema, setMapSchema] = useState('');
  const [mapContextDirty, setMapContextDirty] = useState(false);
  const [selectedFindingIds, setSelectedFindingIds] = useState<number[]>([]);
  const [expandedStructuredRules, setExpandedStructuredRules] = useState<Set<number>>(() => new Set());
  const [structuredEditor, setStructuredEditor] = useState<StructuredRuleEditor | null>(null);
  const [structuredSaving, setStructuredSaving] = useState(false);

  const effectivePolicyId = selectedPolicyId;
  const selectedPolicy = policies.find((policy) => policy.id === effectivePolicyId) || null;
  const rulesQuery = usePolicyRules(effectivePolicyId);
  const rules = rulesQuery.data || [];
  const effectiveMapDataSourceId = mapContextDirty
    ? mapDataSourceId
    : selectedPolicy?.dataSourceId
      ? String(selectedPolicy.dataSourceId)
      : '';
  const effectiveMapSchema = mapContextDirty ? mapSchema : selectedPolicy?.schemaName || '';
  const effectiveRuleSchema = ruleDraft.schemaName || selectedPolicy?.schemaName || '';
  const effectiveRuleFunction = ruleDraft.functionName || (functions.includes('FIRST_NAME') ? 'FIRST_NAME' : functions[0] || '');
  const mapSourceNumber = numberOrNull(effectiveMapDataSourceId);
  const findingsQuery = useDiscoveryFindings(mapSourceNumber, effectiveMapSchema);
  const findings = useMemo(() => findingsQuery.data || [], [findingsQuery.data]);

  const dataSourceOptions = dataSources.map((source) => ({
    value: String(source.id),
    label: `${source.name} (${source.kind || source.role || 'DB'})`
  }));

  const createPolicy = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Policy management permission is required.');
      return apiPost<MaskingPolicy>('/api/policies', {
        name: policyDraft.name.trim(),
        description: policyDraft.description.trim() || null,
        dataSourceId: numberOrNull(policyDraft.dataSourceId),
        schemaName: policyDraft.schemaName.trim() || null
      });
    },
    onSuccess: (created) => {
      notifications.show({ color: 'green', title: 'Policy created', message: created.name });
      setPolicyDraft(emptyPolicyDraft);
      setSelectedPolicyId(created.id);
      setCreateOpened(false);
      setEditorOpened(true);
      setMapContextDirty(false);
      setRuleDraft(emptyRuleDraft);
      queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create policy', message: (error as Error).message })
  });

  const deletePolicy = useMutation({
    mutationFn: (id: number) => {
      if (!canManage) throw new Error('Policy management permission is required.');
      return apiFetch(`/api/policies/${id}`, { method: 'DELETE' });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Policy deleted', message: 'Rules were removed with the policy.' });
      setSelectedPolicyId(null);
      setEditorOpened(false);
      queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not delete policy', message: (error as Error).message })
  });

  const addRule = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Policy management permission is required.');
      return apiPost<MaskingRule>(`/api/policies/${effectivePolicyId}/rules`, {
        schemaName: effectiveRuleSchema.trim() || null,
        tableName: ruleDraft.tableName.trim(),
        columnName: ruleDraft.columnName.trim(),
        function: effectiveRuleFunction,
        param1: ruleDraft.param1 || null,
        param2: ruleDraft.param2 || null
      });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Rule added', message: `${ruleDraft.tableName}.${ruleDraft.columnName}` });
      setRuleDraft((current) => ({ ...emptyRuleDraft, schemaName: current.schemaName, functionName: current.functionName }));
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not add rule', message: (error as Error).message })
  });

  const patchRule = async (rule: MaskingRule, patch: Record<string, string | null>) => {
    if (!canManage) return;
    try {
      await apiPatch<MaskingRule>(`/api/policies/rules/${rule.id}`, patch);
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Rule update failed', message: (error as Error).message });
    }
  };

  const toggleStructuredRule = (ruleId: number) => {
    setExpandedStructuredRules((current) => {
      const next = new Set(current);
      if (next.has(ruleId)) next.delete(ruleId);
      else next.add(ruleId);
      return next;
    });
  };

  const openStructuredRuleEditor = (parent: MaskingRule, config: StructuredRuleConfig, index: number) => {
    if (!canManage) return;
    const leaf = config.rules[index];
    setStructuredEditor({
      parent,
      config,
      index,
      functionName: leaf.function,
      param1: leaf.param1 || '',
      param2: leaf.param2 || ''
    });
  };

  const applyStructuredRule = async () => {
    if (!canManage || !structuredEditor) return;
    setStructuredSaving(true);
    const { parent, config, index, functionName, param1, param2 } = structuredEditor;
    const nextRules = config.rules.map((leaf, leafIndex) => leafIndex === index
      ? { ...leaf, function: functionName, param1: param1 || null, param2: param2 || null }
      : leaf);
    try {
      await apiPatch<MaskingRule>(`/api/policies/rules/${parent.id}`, {
        structuredConfig: JSON.stringify({ ...config, rules: nextRules })
      });
      notifications.show({
        color: 'green',
        title: 'Structured rule applied',
        message: `${structuredLeafLabel(nextRules[index].selector)} now uses ${functionName}.`
      });
      setStructuredEditor(null);
      await queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Structured rule update failed', message: (error as Error).message });
    } finally {
      setStructuredSaving(false);
    }
  };

  const removeRule = async (rule: MaskingRule) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Delete masking rule',
      message: `Delete ${ruleSignature(rule)} from this policy?`,
      okText: 'Delete',
      danger: true
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/policies/rules/${rule.id}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Rule removed', message: ruleSignature(rule) });
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not remove rule', message: (error as Error).message });
    }
  };

  const addMappedRules = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error('Policy management permission is required.');
      if (!effectivePolicyId) throw new Error('Open a policy first.');
      const selected = findings.filter((finding) => selectedFindingIds.includes(finding.id));
      for (const finding of selected) {
        const fn = finding.suggestedFunction || 'FORMAT_PRESERVE';
        const defaults = defaultMaskParamsForMap(fn, finding.piiType);
        await apiPost<MaskingRule>(`/api/policies/${effectivePolicyId}/rules`, {
          schemaName: finding.schemaName || effectiveMapSchema || null,
          tableName: finding.tableName,
          columnName: finding.columnName,
          function: fn,
          param1: finding.suggestedParam1 || defaults.param1,
          param2: finding.suggestedParam2 || defaults.param2
        });
      }
      return selected.length;
    },
    onSuccess: (count) => {
      notifications.show({ color: 'green', title: 'Discovery rules added', message: `${count} rule(s) added to ${selectedPolicy?.name || 'policy'}.` });
      setSelectedFindingIds([]);
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not bind discovery findings', message: (error as Error).message })
  });

  const filteredPolicies = useMemo(() => {
    const q = policySearch.trim().toLowerCase();
    if (!q) return policies;
    return policies.filter((policy) => `${policy.name} ${policy.description || ''} ${policy.schemaName || ''}`.toLowerCase().includes(q));
  }, [policies, policySearch]);

  const findingsByTable = useMemo(() => {
    const map = new Map<string, DiscoveryFinding[]>();
    for (const finding of findings) {
      const list = map.get(finding.tableName) || [];
      list.push(finding);
      map.set(finding.tableName, list);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [findings]);

  const selectedRuleReady = !!effectivePolicyId && !!ruleDraft.tableName.trim() && !!ruleDraft.columnName.trim() && !!effectiveRuleFunction;
  const selectedFindingSet = new Set(selectedFindingIds);

  const toggleFinding = (id: number, checked: boolean) => {
    if (!canManage) return;
    setSelectedFindingIds((ids) => (checked ? Array.from(new Set([...ids, id])) : ids.filter((item) => item !== id)));
  };

  const toggleTable = (rows: DiscoveryFinding[], checked: boolean) => {
    if (!canManage) return;
    const ids = rows.map((row) => row.id);
    setSelectedFindingIds((current) => (checked ? Array.from(new Set([...current, ...ids])) : current.filter((id) => !ids.includes(id))));
  };

  const selectPolicy = (policy: MaskingPolicy) => {
    setSelectedPolicyId(policy.id);
    setEditorOpened(true);
    setMapContextDirty(false);
    setSelectedFindingIds([]);
    setRuleDraft((current) => ({ ...current, schemaName: policy.schemaName || '' }));
  };

  const removePolicy = async (policy: MaskingPolicy) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Delete masking policy',
      message: `Delete "${policy.name}" and all of its rules? DataScope and saved jobs that reference it may no longer be runnable.`,
      okText: 'Delete',
      danger: true
    });
    if (ok) deletePolicy.mutate(policy.id);
  };

  return (
    <main className="forge-page masking-page">
      {confirmElement}
      <MaskingHeader
        eyebrow="Mask"
        title="Masking Policies"
        description="Govern reusable masking rules for discovery, DataScope, and provision runs."
        action={
          <Group gap="xs">
            <Tooltip label="Refresh policies">
              <ActionIcon size="lg" variant="default" aria-label="Refresh policies" onClick={() => policiesQuery.refetch()}>
                <IconRefresh size={17} />
              </ActionIcon>
            </Tooltip>
            {canManage ? (
              <Button leftSection={<IconPlus size={16} />} onClick={() => {
                if (canManage) setCreateOpened(true);
              }}>
                New policy
              </Button>
            ) : null}
          </Group>
        }
      />

      <QueryErrorBanner
        errors={[policiesQuery.error, functionsQuery.error, scriptsQuery.error, valueListsQuery.error, lookupReferencesQuery.error, dataSourcesQuery.error, rulesQuery.error, findingsQuery.error]}
        onRetry={() => Promise.all([policiesQuery.refetch(), functionsQuery.refetch(), scriptsQuery.refetch(), valueListsQuery.refetch(), lookupReferencesQuery.refetch(), dataSourcesQuery.refetch(), rulesQuery.refetch(), findingsQuery.refetch()])}
        title="Masking Policy could not load all backend data"
      />

      <Paper className="forge-card masking-panel" p={0}>
        <div className="masking-panel-head">
          <div>
            <Group gap="xs">
              <Text fw={800}>Policy inventory</Text>
              <Badge variant="light">{filteredPolicies.length}</Badge>
            </Group>
            <Text size="sm" c="dimmed">Open a policy only when its rules need attention.</Text>
          </div>
          <TextInput
            placeholder="Search name, schema, or description"
            value={policySearch}
            onChange={(event) => setPolicySearch(safeInputValue(event))}
            w={360}
          />
        </div>
        {filteredPolicies.length ? (
          <div className="forge-grid-panel">
            <table className="forge-table masking-policy-inventory">
              <thead>
                <tr>
                  <th>Policy</th>
                  <th>Scope</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredPolicies.map((policy) => (
                  <tr key={policy.id}>
                    <td>
                      <Text fw={780}>{policy.name}</Text>
                      <Text size="xs" c="dimmed">{policy.description || 'Reusable masking rule set'}</Text>
                    </td>
                    <td>
                      <Text size="sm">{policy.schemaName || 'Any schema'}</Text>
                      <Text size="xs" c="dimmed">
                        {policy.dataSourceId ? dataSources.find((source) => source.id === policy.dataSourceId)?.name || `Source ${policy.dataSourceId}` : 'Any data source'}
                      </Text>
                    </td>
                    <td><Badge variant="light" color="green">{policy.status || 'ACTIVE'}</Badge></td>
                    <td><Text size="sm">{formatDate(policy.createdAt)}</Text></td>
                    <td>
                      <Group gap={4} wrap="nowrap">
                        <Button size="xs" variant="subtle" leftSection={<IconEdit size={15} />} onClick={() => selectPolicy(policy)}>
                          {canManage ? 'Open' : 'View'}
                        </Button>
                        {canManage ? (
                          <Tooltip label={`Delete ${policy.name}`}>
                            <ActionIcon variant="subtle" color="red" aria-label={`Delete ${policy.name}`} onClick={() => void removePolicy(policy)}>
                              <IconTrash size={16} />
                            </ActionIcon>
                          </Tooltip>
                        ) : null}
                      </Group>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyPanel title="No policies found" detail="Create a policy, or generate one from PII Discovery." />
        )}
      </Paper>

      <Drawer opened={canManage && createOpened} onClose={() => setCreateOpened(false)} position="right" size="lg" title="New masking policy">
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            Bind to a data source and schema when the policy is application-specific, or leave both blank to keep it reusable.
          </Text>
          <NameInput
            label="Policy name"
            description="8 to 120 characters"
            value={policyDraft.name}
            placeholder="CUSTOMER360-MASK"
            maxLength={120}
            onChange={(value) => setPolicyDraft({ ...policyDraft, name: value })}
          />
          <Select label="Data source" data={dataSourceOptions} value={policyDraft.dataSourceId || null} clearable searchable onChange={(value) => setPolicyDraft({ ...policyDraft, dataSourceId: value || '' })} />
          <TextInput label="Schema" value={policyDraft.schemaName} placeholder="optional" onChange={(event) => setPolicyDraft({ ...policyDraft, schemaName: safeInputValue(event) })} {...technicalInputProps} />
          <TextInput label="Description" value={policyDraft.description} onChange={(event) => setPolicyDraft({ ...policyDraft, description: safeInputValue(event) })} />
          <Group justify="flex-end" mt="sm">
            <Button variant="default" onClick={() => setCreateOpened(false)}>Cancel</Button>
            <Button leftSection={<IconPlus size={16} />} loading={createPolicy.isPending} disabled={!canManage || policyDraft.name.trim().length < 8} onClick={() => {
              if (canManage) createPolicy.mutate();
            }}>
              Create policy
            </Button>
          </Group>
        </Stack>
      </Drawer>

      <Modal opened={editorOpened && Boolean(selectedPolicy)} onClose={() => setEditorOpened(false)} fullScreen withCloseButton={false}>
        <Paper className="masking-panel masking-policy-editor" p={0}>
          {selectedPolicy ? (
            <>
              <div className="masking-panel-head">
                <div>
                  <Group gap="xs">
                    <Text fw={820}>{selectedPolicy.name}</Text>
                    <Badge variant="light">{selectedPolicy.schemaName || 'Any schema'}</Badge>
                  </Group>
                  <Text size="sm" c="dimmed">
                    {selectedPolicy.description || 'No description'} · created {formatDate(selectedPolicy.createdAt)}
                  </Text>
                </div>
                <Group gap="xs">
                  {canManage ? <InlineDanger onClick={() => void removePolicy(selectedPolicy)}>Delete policy</InlineDanger> : null}
                  <Button variant="default" leftSection={<IconX size={16} />} onClick={() => setEditorOpened(false)}>
                    Close workspace
                  </Button>
                </Group>
              </div>
              <Tabs defaultValue="rules" classNames={{ list: 'forge-tabs-list' }}>
                <Tabs.List px="md">
                  <Tabs.Tab value="rules" leftSection={<IconShieldCheck size={15} />}>Rules</Tabs.Tab>
                  <Tabs.Tab value="discovery" leftSection={<IconLink size={15} />}>Bind from Discovery</Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="rules" p="md">
                  {canManage ? (
                  <>
                  <SimpleGrid cols={{ base: 1, md: isLookupOptionsFunction(effectiveRuleFunction) ? 4 : 6 }} spacing="sm" className="masking-rule-add-grid">
                    <TextInput label="Schema" value={effectiveRuleSchema} placeholder="optional" onChange={(event) => setRuleDraft({ ...ruleDraft, schemaName: safeInputValue(event) })} {...technicalInputProps} />
                    <TextInput label="Table" value={ruleDraft.tableName} onChange={(event) => setRuleDraft({ ...ruleDraft, tableName: safeInputValue(event) })} {...technicalInputProps} />
                    <TextInput label="Column" value={ruleDraft.columnName} onChange={(event) => setRuleDraft({ ...ruleDraft, columnName: safeInputValue(event) })} {...technicalInputProps} />
                    <Select label="Function" data={functions} searchable value={effectiveRuleFunction || null} onChange={(value) => setRuleDraft({ ...ruleDraft, functionName: value || '', param1: '', param2: '' })} />
                  </SimpleGrid>
                  {isLookupOptionsFunction(effectiveRuleFunction) ? (
                    <div className="masking-lookup-policy-builder">
                      <LookupOptionsBuilder
                        functionName={effectiveRuleFunction}
                        param1={ruleDraft.param1}
                        param2={ruleDraft.param2}
                        onParam1Change={(value) => setRuleDraft({ ...ruleDraft, param1: value })}
                        onParam2Change={(value) => setRuleDraft({ ...ruleDraft, param2: value })}
                        lookupReferences={lookupReferences}
                      />
                    </div>
                  ) : (
                    <SimpleGrid cols={{ base: 1, md: 2 }} spacing="sm" mt="sm">
                      <ParamControl functionName={effectiveRuleFunction} index={1} value={ruleDraft.param1} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setRuleDraft({ ...ruleDraft, param1: value })} />
                      <ParamControl functionName={effectiveRuleFunction} index={2} value={ruleDraft.param2} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setRuleDraft({ ...ruleDraft, param2: value })} />
                    </SimpleGrid>
                  )}
                  <Group justify="flex-end" mt="sm">
                    <Button leftSection={<IconPlus size={16} />} loading={addRule.isPending} disabled={!canManage || !selectedRuleReady} onClick={() => {
                      if (canManage) addRule.mutate();
                    }}>
                      Add rule
                    </Button>
                  </Group>
                  </>
                  ) : null}

                  <div className="forge-grid-panel masking-rules-table">
                    <table className="forge-table">
                      <thead>
                        <tr>
                          <th>Column</th>
                          <th>Function</th>
                          <th>Param 1</th>
                          <th>Param 2</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {rules.map((rule) => {
                          const structured = parseStructuredRuleConfig(rule.structuredConfig);
                          const structuredOpen = expandedStructuredRules.has(rule.id);
                          return <Fragment key={rule.id}>
                            <tr>
                              <td>
                                <Text fw={760}>{rule.columnName}</Text>
                                <Text size="xs" c="dimmed" className="masking-mono-line">
                                  {rule.schemaName ? `${rule.schemaName}.` : ''}{rule.tableName}
                                </Text>
                                {structured ? <Button
                                  mt={5}
                                  size="compact-xs"
                                  variant="subtle"
                                  leftSection={structuredOpen ? <IconChevronDown size={13} /> : <IconChevronRight size={13} />}
                                  onClick={() => toggleStructuredRule(rule.id)}
                                >
                                  {structured.rules.length} {structured.format} field{structured.rules.length === 1 ? '' : 's'}
                                </Button> : null}
                              </td>
                              <td>
                                {structured ? <Badge variant="light" color="teal">Path-aware {structured.format}</Badge> : <Select size="xs" data={functions} searchable value={rule.function} disabled={!canManage} onChange={(value) => {
                                  if (canManage && value) void patchRule(rule, { function: value, param1: null, param2: null });
                                }} />}
                              </td>
                              <td>
                                {structured ? (
                                  <Text size="xs" c="dimmed">Configured per logical field</Text>
                                ) : isLookupOptionsFunction(rule.function) ? (
                                  <Text size="xs" c="dimmed">Configured below</Text>
                                ) : canManage ? (
                                  <ParamControl functionName={rule.function} index={1} value={rule.param1 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => void patchRule(rule, { param1: value || null })} />
                                ) : (
                                  <TextInput size="xs" label="Param 1" value={rule.param1 || ''} readOnly {...technicalInputProps} />
                                )}
                              </td>
                              <td>
                                {structured ? (
                                  <Text size="xs" c="dimmed">Expand to review and apply</Text>
                                ) : isLookupOptionsFunction(rule.function) ? (
                                  <Text size="xs" c="dimmed">Optim-style source/hash/options</Text>
                                ) : canManage ? (
                                  <ParamControl functionName={rule.function} index={2} value={rule.param2 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => void patchRule(rule, { param2: value || null })} />
                                ) : (
                                  <TextInput size="xs" label="Param 2" value={rule.param2 || ''} readOnly {...technicalInputProps} />
                                )}
                              </td>
                              <td>
                                {canManage ? (
                                  <Tooltip label="Delete rule">
                                    <ActionIcon variant="subtle" color="red" aria-label={`Delete rule ${ruleSignature(rule)}`} onClick={() => removeRule(rule)}>
                                      <IconTrash size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                ) : null}
                              </td>
                            </tr>
                            {!structured && isLookupOptionsFunction(rule.function) ? (
                              <tr>
                                <td colSpan={5}>
                                  <LookupOptionsBuilder
                                    functionName={rule.function}
                                    param1={rule.param1 || ''}
                                    param2={rule.param2 || ''}
                                    onParam1Change={(value) => void patchRule(rule, { param1: value || null })}
                                    onParam2Change={(value) => void patchRule(rule, { param2: value || null })}
                                    lookupReferences={lookupReferences}
                                  />
                                </td>
                              </tr>
                            ) : null}
                            {structured && structuredOpen ? (
                              <tr className="masking-structured-rule-detail">
                                <td colSpan={5}>
                                  <div className="masking-structured-rule-head">
                                    <div>
                                      <Text fw={780}>Logical fields inside {rule.columnName}</Text>
                                      <Text size="xs" c="dimmed">Each selector is compiled into this physical column rule and runs during preview, provisioning, and in-place masking.</Text>
                                    </div>
                                    <Badge variant="light" color="teal">{structured.format} · {structured.rules.length} rules</Badge>
                                  </div>
                                  <div className="masking-structured-rule-list">
                                    {structured.rules.map((leaf, index) => <div className="masking-structured-rule-row" key={`${rule.id}-${leaf.selector}`}>
                                      <div>
                                        <Text size="sm" fw={740}>{structuredLeafLabel(leaf.selector)}</Text>
                                        <Text size="xs" c="dimmed" className="masking-mono-line" title={leaf.selector}>{leaf.selector}</Text>
                                      </div>
                                      <Badge size="sm" variant="light" color="blue">{structuredPiiType(leaf)}</Badge>
                                      <div>
                                        <Text size="sm" fw={720}>{leaf.function}</Text>
                                        <Text size="xs" c="dimmed">{[leaf.param1, leaf.param2].filter(Boolean).join(' / ') || 'Default parameters'}</Text>
                                      </div>
                                      {canManage ? <Button size="compact-sm" variant="light" leftSection={<IconEdit size={14} />} onClick={() => openStructuredRuleEditor(rule, structured, index)}>Apply rule</Button> : null}
                                    </div>)}
                                  </div>
                                </td>
                              </tr>
                            ) : null}
                          </Fragment>
                        })}
                        {!rules.length ? (
                          <tr>
                            <td colSpan={5}>
                              <Text c="dimmed">No rules yet. Add one manually or bind from Discovery.</Text>
                            </td>
                          </tr>
                        ) : null}
                      </tbody>
                    </table>
                  </div>
                </Tabs.Panel>
                <Tabs.Panel value="discovery" p="md">
                  <Group justify="space-between" align="flex-start">
                    <div>
                      <Text fw={780}>Bind discovery findings</Text>
                      <Text size="sm" c="dimmed">
                        Load approved/suggested PII findings, select only the tables and columns you want, and add them as rules.
                      </Text>
                    </div>
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => findingsQuery.refetch()} disabled={!mapSourceNumber || !effectiveMapSchema.trim()}>
                      Load
                    </Button>
                  </Group>
                  <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm" mt="md">
                    <Select label="Discovery data source" data={dataSourceOptions} value={effectiveMapDataSourceId || null} searchable clearable onChange={(value) => { setMapContextDirty(true); setMapDataSourceId(value || ''); }} />
                    <TextInput label="Schema" value={effectiveMapSchema} onChange={(event) => { setMapContextDirty(true); setMapSchema(safeInputValue(event)); }} {...technicalInputProps} />
                    {canManage ? (
                      <Button mt={22} leftSection={<IconLink size={16} />} loading={addMappedRules.isPending} disabled={!canManage || !selectedFindingIds.length} onClick={() => {
                        if (canManage) addMappedRules.mutate();
                      }}>
                        Add selected ({selectedFindingIds.length})
                      </Button>
                    ) : null}
                  </SimpleGrid>
                  <div className="masking-discovery-map">
                    {findingsByTable.map(([table, rows]) => {
                      const allSelected = rows.every((row) => selectedFindingSet.has(row.id));
                      return (
                        <Paper key={table} className="masking-finding-table" p="sm">
                          <Group justify="space-between">
                            <Checkbox label={<Text fw={780}>{table}</Text>} checked={allSelected} disabled={!canManage} onChange={(event) => toggleTable(rows, event.currentTarget.checked)} />
                            <Badge variant="light">{rows.length} finding{rows.length === 1 ? '' : 's'}</Badge>
                          </Group>
                          <div className="masking-finding-list">
                            {rows.map((finding) => (
                              <Checkbox
                                key={finding.id}
                                checked={selectedFindingSet.has(finding.id)}
                                disabled={!canManage}
                                onChange={(event) => toggleFinding(finding.id, event.currentTarget.checked)}
                                label={
                                  <span>
                                    <b>{finding.columnName}</b> <span className="masking-muted">· {finding.piiType} · {finding.suggestedFunction || 'FORMAT_PRESERVE'}</span>
                                  </span>
                                }
                              />
                            ))}
                          </div>
                        </Paper>
                      );
                    })}
                    {!findingsByTable.length ? (
                      <EmptyPanel
                        title={findingsQuery.isFetching ? 'Loading discovery findings...' : 'No discovery findings loaded'}
                        detail="Pick a data source and schema where PII Discovery has run. Then select only the findings you want this policy to own."
                      />
                    ) : null}
                  </div>
                </Tabs.Panel>
              </Tabs>
            </>
          ) : (
            <EmptyPanel title="Policy unavailable" detail="Close this workspace and open a policy from the inventory." />
          )}
        </Paper>
      </Modal>

      <Drawer
        opened={Boolean(structuredEditor)}
        onClose={() => setStructuredEditor(null)}
        position="right"
        size="lg"
        zIndex={500}
        title={structuredEditor ? `Apply rule to ${structuredLeafLabel(structuredEditor.config.rules[structuredEditor.index].selector)}` : 'Apply structured rule'}
      >
        {structuredEditor ? (() => {
          const leaf = structuredEditor.config.rules[structuredEditor.index];
          const piiType = structuredPiiType(leaf);
          return (
            <Stack gap="md">
              <Paper className="masking-structured-editor-context" p="md">
                <Group justify="space-between" align="flex-start">
                  <div>
                    <Text size="xs" tt="uppercase" fw={800} c="dimmed">Physical column</Text>
                    <Text fw={780}>{structuredEditor.parent.tableName}.{structuredEditor.parent.columnName}</Text>
                  </div>
                  <Badge variant="light" color="teal">{structuredEditor.config.format}</Badge>
                </Group>
                <Text mt="sm" size="xs" tt="uppercase" fw={800} c="dimmed">Logical selector</Text>
                <Text className="masking-mono-line" title={leaf.selector}>{leaf.selector}</Text>
                <Badge mt="sm" variant="light" color="blue">{piiType}</Badge>
              </Paper>

              <Select
                label="Masking function"
                description="Applied only to this logical field inside the structured value."
                data={functions}
                searchable
                value={structuredEditor.functionName}
                onChange={(value) => {
                  if (!value) return;
                  const defaults = defaultMaskParamsForMap(value, piiType);
                  setStructuredEditor((current) => current ? {
                    ...current,
                    functionName: value,
                    param1: defaults.param1 || '',
                    param2: defaults.param2 || ''
                  } : null);
                }}
              />

              {isLookupOptionsFunction(structuredEditor.functionName) ? (
                <LookupOptionsBuilder
                  functionName={structuredEditor.functionName}
                  param1={structuredEditor.param1}
                  param2={structuredEditor.param2}
                  onParam1Change={(value) => setStructuredEditor((current) => current ? { ...current, param1: value } : null)}
                  onParam2Change={(value) => setStructuredEditor((current) => current ? { ...current, param2: value } : null)}
                  lookupReferences={lookupReferences}
                />
              ) : (
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                  <ParamControl
                    functionName={structuredEditor.functionName}
                    index={1}
                    value={structuredEditor.param1}
                    scripts={scripts}
                    valueLists={valueLists}
                    lookupReferences={lookupReferences}
                    onChange={(value) => setStructuredEditor((current) => current ? { ...current, param1: value } : null)}
                  />
                  <ParamControl
                    functionName={structuredEditor.functionName}
                    index={2}
                    value={structuredEditor.param2}
                    scripts={scripts}
                    valueLists={valueLists}
                    lookupReferences={lookupReferences}
                    onChange={(value) => setStructuredEditor((current) => current ? { ...current, param2: value } : null)}
                  />
                </SimpleGrid>
              )}

              <Text size="sm" c="dimmed">
                Saving recompiles the parent structured-column rule. Preview, provisioning, and in-place masking use this exact leaf configuration.
              </Text>
              <Group justify="flex-end">
                <Button variant="default" onClick={() => setStructuredEditor(null)}>Discard</Button>
                <Button loading={structuredSaving} disabled={!structuredEditor.functionName} onClick={() => void applyStructuredRule()}>
                  Apply rule
                </Button>
              </Group>
            </Stack>
          );
        })() : null}
      </Drawer>
    </main>
  );
}

function parseStructuredRuleConfig(value?: string | null): StructuredRuleConfig | null {
  if (!value?.trim()) return null;
  try {
    const parsed = JSON.parse(value) as { format?: unknown; rules?: unknown };
    if (!Array.isArray(parsed.rules)) return null;
    const rules = parsed.rules.flatMap((candidate) => {
      if (!candidate || typeof candidate !== 'object') return [];
      const source = candidate as Record<string, unknown>;
      const selector = typeof source.selector === 'string' ? source.selector.trim() : '';
      const functionName = typeof source.function === 'string' ? source.function.trim().toUpperCase() : '';
      if (!selector || !functionName) return [];
      return [{
        selector,
        function: functionName,
        salt: typeof source.salt === 'string' ? source.salt : null,
        param1: typeof source.param1 === 'string' ? source.param1 : null,
        param2: typeof source.param2 === 'string' ? source.param2 : null
      } satisfies StructuredLeafRule];
    });
    if (!rules.length) return null;
    return {
      format: typeof parsed.format === 'string' && parsed.format.trim() ? parsed.format.trim().toUpperCase() : 'TEMENOS',
      rules
    };
  } catch {
    return null;
  }
}

function structuredLeafLabel(selector: string) {
  const normalized = selector.replace(/\[\*\]/g, '').replace(/\[\d+\]/g, '');
  const parts = normalized.split(/[./]/).filter(Boolean);
  return parts[parts.length - 1] || selector;
}

function structuredPiiType(leaf: StructuredLeafRule) {
  const salt = leaf.salt?.trim() || '';
  if (salt.toLowerCase().startsWith('pii.')) return salt.slice(4).replace(/[^a-z0-9]+/gi, '_').toUpperCase();
  return leaf.function;
}
