'use client';

import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Code,
  Divider,
  Group,
  Loader,
  Modal,
  ScrollArea,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  Textarea,
  TextInput,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconArchive,
  IconBox,
  IconBrackets,
  IconCheck,
  IconCopy,
  IconDeviceFloppy,
  IconGitBranch,
  IconPlayerPlay,
  IconPlus,
  IconRocket,
  IconSearch,
  IconVersions,
  IconX
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { useConfirm } from '@/components/confirm';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import {
  useDataSources,
  useSyntheticAsset,
  useSyntheticAssets,
  useSyntheticAssetTypes,
  useSyntheticGenerators
} from '../hooks';
import { SyntheticAssetDefinitionForm } from './synthetic-asset-forms';
import type {
  CompiledSyntheticScenario,
  SyntheticAssetDetail,
  SyntheticAssetSummary,
  SyntheticAssetType,
  SyntheticPlan
} from '../types';

const TYPE_ICONS: Record<SyntheticAssetType, typeof IconBox> = {
  DATA_MODEL: IconBox,
  FIELD_CONTRACT: IconBrackets,
  GENERATION_RULE: IconGitBranch,
  DELIVERY_PROFILE: IconRocket,
  GENERATION_SCENARIO: IconPlayerPlay
};

const TYPE_SHORT: Record<SyntheticAssetType, string> = {
  DATA_MODEL: 'Models',
  FIELD_CONTRACT: 'Contracts',
  GENERATION_RULE: 'Rules',
  DELIVERY_PROFILE: 'Delivery',
  GENERATION_SCENARIO: 'Scenarios'
};

type CreateDraft = {
  assetType: SyntheticAssetType;
  name: string;
  description: string;
  visibility: string;
};

export function SyntheticAssetLibrary({
  opened,
  onClose,
  onLoadPlan
}: {
  opened: boolean;
  onClose: () => void;
  onLoadPlan: (plan: SyntheticPlan) => void;
}) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('synthetic.manage');
  const canRun = can('synthetic.run');
  const { confirm, confirmElement } = useConfirm();
  const typeQuery = useSyntheticAssetTypes();
  const allAssetsQuery = useSyntheticAssets();
  const [type, setType] = useState<string>('ALL');
  const [status, setStatus] = useState<string>('ACTIVE');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [cloneSource, setCloneSource] = useState<SyntheticAssetSummary | null>(null);
  const [cloneName, setCloneName] = useState('');
  const [createDraft, setCreateDraft] = useState<CreateDraft>({
    assetType: 'DATA_MODEL',
    name: '',
    description: '',
    visibility: 'GROUP'
  });

  const assets = allAssetsQuery.data || [];
  const visibleAssets = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return assets.filter((asset) => {
      if (type !== 'ALL' && asset.assetType !== type) return false;
      if (status === 'ACTIVE' && asset.status === 'ARCHIVED') return false;
      if (status !== 'ALL' && status !== 'ACTIVE' && asset.status !== status) return false;
      return !needle
        || asset.name.toLowerCase().includes(needle)
        || String(asset.description || '').toLowerCase().includes(needle);
    });
  }, [assets, search, status, type]);

  useEffect(() => {
    if (!opened) return;
    if (selectedId && visibleAssets.some((asset) => asset.id === selectedId)) return;
    setSelectedId(visibleAssets[0]?.id || null);
  }, [opened, selectedId, visibleAssets]);

  const refresh = async (id?: string | null) => {
    await queryClient.invalidateQueries({ queryKey: ['synthetic', 'assets'] });
    if (id) await queryClient.invalidateQueries({ queryKey: keys.synthetic.asset(id) });
  };

  const createAsset = async () => {
    const definition = typeQuery.data?.find((entry) => entry.type === createDraft.assetType);
    try {
      const saved = await apiPost<SyntheticAssetDetail>('/api/synthetic/assets', {
        ...createDraft,
        content: definition?.starter || {}
      });
      await refresh(saved.asset.id);
      setSelectedId(saved.asset.id);
      setCreating(false);
      setCreateDraft({ assetType: createDraft.assetType, name: '', description: '', visibility: 'GROUP' });
      notifications.show({ color: 'green', title: 'Reusable asset created', message: 'The validated draft is ready to configure.' });
    } catch (error) {
      notifyError('Could not create asset', error);
    }
  };

  const cloneAsset = async () => {
    if (!cloneSource) return;
    try {
      const saved = await apiPost<SyntheticAssetDetail>(`/api/synthetic/assets/${cloneSource.id}/clone`, {
        name: cloneName.trim(),
        description: cloneSource.description || '',
        version: cloneSource.currentVersion || null
      });
      await refresh(saved.asset.id);
      setSelectedId(saved.asset.id);
      setCloneSource(null);
      notifications.show({ color: 'green', title: 'Asset cloned', message: `${saved.asset.name} is an independent draft.` });
    } catch (error) {
      notifyError('Could not clone asset', error);
    }
  };

  const archive = async (asset: SyntheticAssetSummary) => {
    const ok = await confirm({
      title: 'Archive reusable asset',
      message: `Archive ${asset.name}? Published versions stay available to already pinned scenarios.`,
      okText: 'Archive asset',
      danger: true
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/synthetic/assets/${asset.id}`, { method: 'DELETE' });
      setSelectedId(null);
      await refresh();
      notifications.show({ color: 'green', title: 'Asset archived', message: 'Published lineage remains intact.' });
    } catch (error) {
      notifyError('Could not archive asset', error);
    }
  };

  return (
    <Modal opened={opened} onClose={onClose} fullScreen padding={0} title={null}>
      {confirmElement}
      <div className="synthetic-assets-workspace">
        <header className="synthetic-assets-header">
          <Group gap="sm" wrap="nowrap">
            <span className="synthetic-page-mark"><IconVersions size={20} /></span>
            <div>
              <Group gap="xs">
                <Text fw={900} size="lg">Reusable synthetic assets</Text>
                <Badge variant="light">{assets.filter((asset) => asset.status !== 'ARCHIVED').length} active</Badge>
              </Group>
              <Text size="sm" c="dimmed">Design once, pin exact versions, compile, and run through the existing generation engine.</Text>
            </div>
          </Group>
          <Group gap="xs">
            {canManage ? <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>New asset</Button> : null}
            <ActionIcon variant="subtle" color="gray" size="lg" onClick={onClose} aria-label="Close asset library"><IconX size={20} /></ActionIcon>
          </Group>
        </header>

        <QueryErrorBanner
          errors={[typeQuery.error, allAssetsQuery.error]}
          onRetry={() => Promise.all([typeQuery.refetch(), allAssetsQuery.refetch()])}
          title="Reusable assets could not be loaded"
        />

        <div className="synthetic-assets-layout">
          <aside className="synthetic-assets-master">
            <TextInput
              leftSection={<IconSearch size={15} />}
              placeholder="Search reusable assets"
              value={search}
              onChange={(event) => setSearch(event.currentTarget.value)}
            />
            <ScrollArea type="auto" offsetScrollbars>
              <Group gap={6} className="synthetic-assets-type-filter">
                <button type="button" className={type === 'ALL' ? 'is-active' : ''} onClick={() => setType('ALL')}>All</button>
                {(typeQuery.data || []).map((item) => (
                  <button type="button" className={type === item.type ? 'is-active' : ''} key={item.type} onClick={() => setType(item.type)}>
                    {TYPE_SHORT[item.type]}
                  </button>
                ))}
              </Group>
            </ScrollArea>
            <SegmentedControl
              fullWidth
              size="xs"
              value={status}
              onChange={setStatus}
              data={[
                { value: 'ACTIVE', label: 'Active' },
                { value: 'DRAFT', label: 'Drafts' },
                { value: 'PUBLISHED', label: 'Published' },
                { value: 'ALL', label: 'All' }
              ]}
            />
            <Divider />
            <ScrollArea className="synthetic-assets-list" type="auto" offsetScrollbars>
              <Stack gap={4}>
                {visibleAssets.map((asset) => {
                  const Icon = TYPE_ICONS[asset.assetType];
                  return (
                    <button
                      type="button"
                      key={asset.id}
                      className={asset.id === selectedId ? 'synthetic-asset-row is-active' : 'synthetic-asset-row'}
                      onClick={() => setSelectedId(asset.id)}
                    >
                      <span><Icon size={17} /></span>
                      <div>
                        <strong>{asset.name}</strong>
                        <small>{TYPE_SHORT[asset.assetType]} / {asset.status.toLowerCase()} / v{asset.currentVersion}</small>
                      </div>
                      <StatusDot status={asset.status} />
                    </button>
                  );
                })}
                {!visibleAssets.length ? (
                  <div className="synthetic-assets-empty">
                    <Text fw={750}>No matching assets</Text>
                    <Text size="sm" c="dimmed">Change the filter or create the first reusable design asset.</Text>
                  </div>
                ) : null}
              </Stack>
            </ScrollArea>
          </aside>

          <main className="synthetic-assets-detail">
            {allAssetsQuery.isLoading || typeQuery.isLoading ? (
              <Group justify="center" mt="xl"><Loader /><Text c="dimmed">Loading asset registry...</Text></Group>
            ) : selectedId ? (
              <AssetEditor
                id={selectedId}
                allAssets={assets}
                canManage={canManage}
                canRun={canRun}
                onRefresh={() => refresh(selectedId)}
                onClone={(asset) => {
                  setCloneSource(asset);
                  setCloneName(`${asset.name} Copy`);
                }}
                onArchive={archive}
                onLoadPlan={onLoadPlan}
              />
            ) : (
              <div className="synthetic-assets-welcome">
                <IconVersions size={34} />
                <Text fw={900} size="xl">Build reusable synthetic capability</Text>
                <Text c="dimmed" maw={620} ta="center">
                  Models define shape, contracts define meaning, rules define values, delivery profiles define destinations, and scenarios pin them into repeatable runs.
                </Text>
              </div>
            )}
          </main>
        </div>
      </div>

      <Modal opened={creating} onClose={() => setCreating(false)} title="Create reusable synthetic asset" size="lg" centered>
        <Stack gap="sm">
          <Select
            label="Asset type"
            data={(typeQuery.data || []).map((item) => ({ value: item.type, label: item.label }))}
            value={createDraft.assetType}
            onChange={(value) => setCreateDraft({ ...createDraft, assetType: (value || 'DATA_MODEL') as SyntheticAssetType })}
          />
          <TextInput
            label="Name"
            description="8-120 characters. Stable and meaningful to the team."
            minLength={8}
            maxLength={120}
            value={createDraft.name}
            onChange={(event) => setCreateDraft({ ...createDraft, name: event.currentTarget.value })}
          />
          <Textarea label="Purpose" minRows={2} maxLength={2000} value={createDraft.description} onChange={(event) => setCreateDraft({ ...createDraft, description: event.currentTarget.value })} />
          <Select label="Visibility" data={['PRIVATE', 'GROUP', 'SHARED']} value={createDraft.visibility} onChange={(value) => setCreateDraft({ ...createDraft, visibility: value || 'GROUP' })} />
          <Alert color="blue" variant="light">
            A validated starter definition is created as a draft. Nothing is executable until you publish an immutable version.
          </Alert>
          <Group justify="flex-end">
            <Button variant="subtle" onClick={() => setCreating(false)}>Cancel</Button>
            <Button disabled={createDraft.name.trim().length < 8} onClick={() => void createAsset()}>Create draft</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={Boolean(cloneSource)} onClose={() => setCloneSource(null)} title="Clone as a new draft" centered>
        <Stack>
          <TextInput label="New asset name" minLength={8} maxLength={120} value={cloneName} onChange={(event) => setCloneName(event.currentTarget.value)} />
          <Text size="sm" c="dimmed">The selected published version is copied without changing the original or its dependants.</Text>
          <Group justify="flex-end">
            <Button variant="subtle" onClick={() => setCloneSource(null)}>Cancel</Button>
            <Button disabled={cloneName.trim().length < 8} onClick={() => void cloneAsset()}>Clone draft</Button>
          </Group>
        </Stack>
      </Modal>
    </Modal>
  );
}

function AssetEditor({
  id,
  allAssets,
  canManage,
  canRun,
  onRefresh,
  onClone,
  onArchive,
  onLoadPlan
}: {
  id: string;
  allAssets: SyntheticAssetSummary[];
  canManage: boolean;
  canRun: boolean;
  onRefresh: () => Promise<void>;
  onClone: (asset: SyntheticAssetSummary) => void;
  onArchive: (asset: SyntheticAssetSummary) => Promise<void>;
  onLoadPlan: (plan: SyntheticPlan) => void;
}) {
  const detailQuery = useSyntheticAsset(id);
  const generatorsQuery = useSyntheticGenerators();
  const dataSourcesQuery = useDataSources();
  const [loadedId, setLoadedId] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [visibility, setVisibility] = useState('GROUP');
  const [definition, setDefinition] = useState('{}');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [compiled, setCompiled] = useState<CompiledSyntheticScenario | null>(null);

  const detail = detailQuery.data;
  useEffect(() => {
    if (!detail || loadedId === detail.asset.id) return;
    setLoadedId(detail.asset.id);
    setName(detail.asset.name);
    setDescription(detail.asset.description || '');
    setVisibility(detail.asset.visibility || 'GROUP');
    setDefinition(JSON.stringify(detail.draft || {}, null, 2));
    setDirty(false);
    setCompiled(null);
  }, [detail, loadedId]);

  const changeDefinition = (value: string) => {
    setDefinition(value);
    setDirty(true);
  };
  const changeGuidedDefinition = (value: Record<string, unknown>) => {
    changeDefinition(JSON.stringify(value, null, 2));
  };

  const parsedDefinition = () => {
    try {
      const value = JSON.parse(definition);
      if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('Definition must be a JSON object.');
      return value as Record<string, unknown>;
    } catch (error) {
      throw new Error(error instanceof Error ? `Invalid definition: ${error.message}` : 'Invalid JSON definition');
    }
  };

  const save = async () => {
    if (!detail) return false;
    setBusy('save');
    try {
      const saved = await apiPut<SyntheticAssetDetail>(`/api/synthetic/assets/${detail.asset.id}`, {
        assetType: detail.asset.assetType,
        name: name.trim(),
        description: description.trim(),
        visibility,
        content: parsedDefinition()
      });
      setDefinition(JSON.stringify(saved.draft, null, 2));
      setDirty(false);
      await onRefresh();
      notifications.show({ color: 'green', title: 'Draft saved', message: 'Server validation passed.' });
      return true;
    } catch (error) {
      notifyError('Could not save draft', error);
      return false;
    } finally {
      setBusy(null);
    }
  };

  const publish = async () => {
    if (!detail) return;
    try {
      if (dirty && !(await save())) return;
      setBusy('publish');
      const published = await apiPost<SyntheticAssetDetail>(`/api/synthetic/assets/${detail.asset.id}/publish`, {});
      setDefinition(JSON.stringify(published.draft, null, 2));
      setDirty(false);
      await onRefresh();
      notifications.show({ color: 'green', title: `Version ${published.asset.currentVersion} published`, message: 'Dependencies are pinned and the version is immutable.' });
    } catch (error) {
      notifyError('Could not publish asset', error);
    } finally {
      setBusy(null);
    }
  };

  const compile = async () => {
    if (!detail) return;
    setBusy('compile');
    try {
      const result = await apiPost<CompiledSyntheticScenario>(`/api/synthetic/assets/${detail.asset.id}/compile`, {});
      setCompiled(result);
      notifications.show({ color: 'green', title: 'Scenario compiled', message: `Plan ${result.planHash.slice(0, 12)} is ready.` });
    } catch (error) {
      notifyError('Scenario could not compile', error);
    } finally {
      setBusy(null);
    }
  };

  const launch = async () => {
    if (!detail) return;
    setBusy('launch');
    try {
      const job = await apiPost<Record<string, unknown>>(`/api/synthetic/assets/${detail.asset.id}/launch`, {});
      notifications.show({ color: 'blue', title: 'Scenario launched', message: `Job ${String(job.id || '')} is visible in Run history.` });
    } catch (error) {
      notifyError('Scenario could not launch', error);
    } finally {
      setBusy(null);
    }
  };

  if (detailQuery.isLoading || !detail) {
    return <Group justify="center" mt="xl"><Loader /><Text c="dimmed">Loading reusable asset...</Text></Group>;
  }

  const isScenario = detail.asset.assetType === 'GENERATION_SCENARIO';
  return (
    <Stack gap={0} h="100%">
      <header className="synthetic-asset-detail-head">
        <div>
          <Group gap="xs">
            <Badge variant="light">{TYPE_SHORT[detail.asset.assetType]}</Badge>
            <StatusBadge status={detail.asset.status} />
            <Text size="xs" c="dimmed">v{detail.asset.currentVersion}</Text>
            {dirty ? <Badge color="yellow" variant="dot">Unsaved draft</Badge> : null}
          </Group>
          <Text fw={900} size="xl" mt={4}>{detail.asset.name}</Text>
          <Text size="sm" c="dimmed">{detail.asset.description || 'No purpose has been recorded.'}</Text>
        </div>
        <Group gap="xs">
          <Tooltip label="Clone published version as a new draft">
            <ActionIcon variant="light" onClick={() => onClone(detail.asset)}><IconCopy size={17} /></ActionIcon>
          </Tooltip>
          {canManage && detail.asset.status !== 'ARCHIVED' ? (
            <Tooltip label="Archive asset">
              <ActionIcon color="red" variant="subtle" onClick={() => void onArchive(detail.asset)}><IconArchive size={17} /></ActionIcon>
            </Tooltip>
          ) : null}
        </Group>
      </header>

      <Tabs defaultValue="definition" className="synthetic-asset-tabs">
        <Tabs.List>
          <Tabs.Tab value="definition" leftSection={<IconBrackets size={15} />}>Definition</Tabs.Tab>
          <Tabs.Tab value="versions" leftSection={<IconVersions size={15} />}>Versions {detail.versions.length ? `(${detail.versions.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="impact" leftSection={<IconGitBranch size={15} />}>Dependencies & impact</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="definition" className="synthetic-asset-tab-panel">
          <ScrollArea h="calc(100vh - 220px)" offsetScrollbars>
            <Stack gap="md" p="lg">
              <SimpleGrid cols={{ base: 1, md: 2 }}>
                <TextInput label="Asset name" minLength={8} maxLength={120} disabled={!canManage} value={name} onChange={(event) => { setName(event.currentTarget.value); setDirty(true); }} />
                <Select label="Visibility" data={['PRIVATE', 'GROUP', 'SHARED']} disabled={!canManage} value={visibility} onChange={(value) => { setVisibility(value || 'GROUP'); setDirty(true); }} />
              </SimpleGrid>
              <Textarea label="Purpose" minRows={2} maxLength={2000} disabled={!canManage} value={description} onChange={(event) => { setDescription(event.currentTarget.value); setDirty(true); }} />

              {isScenario ? (
                <ScenarioAssembly
                  definition={definition}
                  assets={allAssets}
                  disabled={!canManage}
                  onChange={changeDefinition}
                />
              ) : null}

              <SyntheticAssetDefinitionForm
                assetType={detail.asset.assetType}
                value={safeObject(definition)}
                assets={allAssets}
                generators={generatorsQuery.data || []}
                dataSources={dataSourcesQuery.data || []}
                disabled={!canManage}
                onChange={changeGuidedDefinition}
              />

              <details className="synthetic-definition-advanced">
                <summary>Advanced definition for administrators</summary>
                <Text size="xs" c="dimmed" mt="xs">
                  Use only for troubleshooting or fields not yet exposed by the guided editor.
                </Text>
                <Textarea
                  mt="sm"
                  aria-label="Advanced versioned asset definition"
                  value={definition}
                  onChange={(event) => changeDefinition(event.currentTarget.value)}
                  disabled={!canManage}
                  autosize
                  minRows={12}
                  maxRows={24}
                  styles={{ input: { fontFamily: 'var(--font-mono, monospace)', fontSize: 13, lineHeight: 1.55 } }}
                />
              </details>

              {compiled ? (
                <Alert color="green" title="Compiled execution manifest">
                  <Group gap="xl">
                    <Text size="sm">Scenario v{compiled.scenarioVersion}</Text>
                    <Text size="sm">{compiled.components.length} pinned component(s)</Text>
                    <Code>{compiled.planHash.slice(0, 16)}</Code>
                    <Button size="compact-xs" variant="light" onClick={() => onLoadPlan(compiled.plan)}>Open plan in Build</Button>
                  </Group>
                </Alert>
              ) : null}
            </Stack>
          </ScrollArea>
          <footer className="synthetic-asset-actionbar">
            <Text size="xs" c="dimmed">
              {detail.asset.currentVersion ? `Published v${detail.asset.currentVersion} remains immutable while this draft changes.` : 'Publish the first immutable version when validation is clean.'}
            </Text>
            <Group gap="xs">
              {isScenario && detail.asset.currentVersion > 0 ? (
                <>
                  <Button variant="light" leftSection={<IconCheck size={16} />} loading={busy === 'compile'} onClick={() => void compile()}>Compile</Button>
                  <Button color="green" leftSection={<IconPlayerPlay size={16} />} disabled={!canRun} loading={busy === 'launch'} onClick={() => void launch()}>Run scenario</Button>
                </>
              ) : null}
              {canManage ? (
                <>
                  <Button variant="light" leftSection={<IconDeviceFloppy size={16} />} disabled={!dirty} loading={busy === 'save'} onClick={() => void save()}>Save draft</Button>
                  <Button leftSection={<IconVersions size={16} />} loading={busy === 'publish'} onClick={() => void publish()}>Publish version</Button>
                </>
              ) : null}
            </Group>
          </footer>
        </Tabs.Panel>

        <Tabs.Panel value="versions" className="synthetic-asset-tab-panel">
          <ScrollArea h="calc(100vh - 220px)" p="lg">
            <Stack gap="xs">
              {detail.versions.map((version) => (
                <div className="synthetic-version-row" key={version.id}>
                  <div>
                    <Text fw={850}>Version {version.version}</Text>
                    <Text size="xs" c="dimmed">Published by {version.publishedBy || 'system'} / {formatTime(version.publishedAt)}</Text>
                  </div>
                  <Group gap="md">
                    <Badge variant="light">{version.compatibilityLevel}</Badge>
                    <Text size="xs">{version.dependencyCount} dependencies</Text>
                    <Code>{version.contentHash.slice(0, 16)}</Code>
                  </Group>
                </div>
              ))}
              {!detail.versions.length ? <EmptyEvidence title="No published versions" detail="Save the draft, then publish its first immutable version." /> : null}
            </Stack>
          </ScrollArea>
        </Tabs.Panel>

        <Tabs.Panel value="impact" className="synthetic-asset-tab-panel">
          <ScrollArea h="calc(100vh - 220px)" p="lg">
            <SimpleGrid cols={{ base: 1, lg: 2 }}>
              <EvidenceList
                title="Pinned dependencies"
                detail="Exact versions this asset consumes."
                rows={detail.dependencies.map((item) => ({
                  title: item.name,
                  subtitle: `${TYPE_SHORT[item.assetType]} / version ${item.version}`,
                  badge: item.kind
                }))}
              />
              <EvidenceList
                title="Downstream impact"
                detail="Published versions that depend on this asset."
                rows={detail.impact.map((item) => ({
                  title: item.name,
                  subtitle: `${TYPE_SHORT[item.assetType]} v${item.ownerVersion} pins this v${item.dependencyVersion}`,
                  badge: item.kind
                }))}
              />
            </SimpleGrid>
          </ScrollArea>
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function ScenarioAssembly({
  definition,
  assets,
  disabled,
  onChange
}: {
  definition: string;
  assets: SyntheticAssetSummary[];
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const parsed = safeObject(definition);
  const models = assets.filter((asset) => asset.assetType === 'DATA_MODEL' && asset.currentVersion > 0 && asset.status !== 'ARCHIVED');
  const deliveries = assets.filter((asset) => asset.assetType === 'DELIVERY_PROFILE' && asset.currentVersion > 0 && asset.status !== 'ARCHIVED');
  const update = (patch: Record<string, unknown>) => onChange(JSON.stringify({ ...parsed, ...patch }, null, 2));
  const modelRef = safeObject(parsed.modelRef);
  const deliveryRef = safeObject(parsed.deliveryRef);

  return (
    <div className="synthetic-scenario-assembly">
      <Group justify="space-between" mb="sm">
        <div>
          <Text fw={850}>Scenario assembly</Text>
          <Text size="xs" c="dimmed">Choose published components. Versions are pinned again at publish time.</Text>
        </div>
        <Badge color="blue" variant="light">Guided</Badge>
      </Group>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <TextInput label="Dataset label" disabled={disabled} value={String(parsed.dataset || '')} onChange={(event) => update({ dataset: event.currentTarget.value })} />
        <TextInput label="Seed" type="number" disabled={disabled} value={String(parsed.seed ?? 42)} onChange={(event) => update({ seed: Number(event.currentTarget.value || 42) })} />
        <Select
          label="Published Data Model"
          searchable
          disabled={disabled}
          data={models.map((asset) => ({ value: asset.id, label: `${asset.name} / v${asset.currentVersion}` }))}
          value={String(modelRef.assetId || '') || null}
          onChange={(value) => {
            const asset = models.find((item) => item.id === value);
            update({ modelRef: asset ? { assetId: asset.id, version: asset.currentVersion } : {} });
          }}
        />
        <Select
          label="Published Delivery Profile"
          searchable
          disabled={disabled}
          data={deliveries.map((asset) => ({ value: asset.id, label: `${asset.name} / v${asset.currentVersion}` }))}
          value={String(deliveryRef.assetId || '') || null}
          onChange={(value) => {
            const asset = deliveries.find((item) => item.id === value);
            update({ deliveryRef: asset ? { assetId: asset.id, version: asset.currentVersion } : {} });
          }}
        />
      </SimpleGrid>
    </div>
  );
}

function EvidenceList({ title, detail, rows }: { title: string; detail: string; rows: Array<{ title: string; subtitle: string; badge: string }> }) {
  return (
    <div className="synthetic-evidence-list">
      <Text fw={850}>{title}</Text>
      <Text size="sm" c="dimmed" mb="md">{detail}</Text>
      <Stack gap={0}>
        {rows.map((row, index) => (
          <div className="synthetic-evidence-row" key={`${row.title}-${index}`}>
            <div><Text fw={750}>{row.title}</Text><Text size="xs" c="dimmed">{row.subtitle}</Text></div>
            <Badge variant="light">{row.badge}</Badge>
          </div>
        ))}
        {!rows.length ? <EmptyEvidence title="Nothing recorded" detail="This published version has no entries in this direction." /> : null}
      </Stack>
    </div>
  );
}

function EmptyEvidence({ title, detail }: { title: string; detail: string }) {
  return <div className="synthetic-assets-empty"><Text fw={750}>{title}</Text><Text size="sm" c="dimmed">{detail}</Text></div>;
}

function StatusDot({ status }: { status: string }) {
  return <span className={`synthetic-asset-status-dot is-${status.toLowerCase()}`} aria-label={status} />;
}

function StatusBadge({ status }: { status: string }) {
  const color = status === 'PUBLISHED' ? 'green' : status === 'DRAFT' ? 'yellow' : status === 'ARCHIVED' ? 'gray' : 'orange';
  return <Badge color={color} variant="light">{status}</Badge>;
}

function safeObject(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  }
  return value && !Array.isArray(value) && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function formatTime(value?: string | null) {
  if (!value) return 'unknown time';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function notifyError(title: string, error: unknown) {
  notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : 'Request failed' });
}
