'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Drawer, Group, Loader, Modal, Paper, Stack, Text, Title, Tooltip } from '@mantine/core';
import { IconDatabase, IconFolderOpen, IconHistory, IconPlus, IconRefresh } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { ErrorBoundary } from '@/components/error-boundary';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { useConfirm } from '@/components/confirm';
import { useUnsavedGuard } from '@/lib/use-unsaved-guard';
import { usePermissions } from '@/lib/use-permissions';
import {
  useBlueprints,
  useDataSources,
  useDrift,
  useMainframeAssets,
  useOverrides,
  usePiiCoverage,
  usePolicies,
  useProfiles,
  useSavedJobs
} from './hooks';
import { BlueprintList } from './components/blueprint-list';
import { SelectedBlueprintWorkspace } from './components/blueprint-workspace';
import { CreateBlueprintPanel } from './components/create-blueprint-panel';
import { ProvisionJobMonitor } from './components/job-monitor';

export function DataScopePage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [libraryOpened, setLibraryOpened] = useState(false);
  const [createOpened, setCreateOpened] = useState(false);
  const [historyOpened, setHistoryOpened] = useState(false);

  // Never let a session-expiry redirect silently destroy unsaved blueprint edits (AUTH-003-05 / DEF-0003).
  useUnsavedGuard(workspaceDirty);

  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const blueprintsQuery = useBlueprints();
  const savedJobsQuery = useSavedJobs();

  const selectedBlueprint = useMemo(
    () => blueprintsQuery.data?.find((item) => item.id === selectedId) || null,
    [blueprintsQuery.data, selectedId]
  );
  const blueprintNames = useMemo(
    () => Object.fromEntries((blueprintsQuery.data || []).map((blueprint) => [blueprint.id, blueprint.name])),
    [blueprintsQuery.data]
  );

  // File-only scopes have no relational catalog. A null query key also prevents
  // cached DB guardrail results from leaking into the mainframe-only workspace.
  const relationalDatasetId = selectedBlueprint?.dataSourceId ? selectedId : null;
  const profilesQuery = useProfiles(relationalDatasetId);
  const piiCoverageQuery = usePiiCoverage(relationalDatasetId);
  const driftQuery = useDrift(relationalDatasetId);
  const overridesQuery = useOverrides(relationalDatasetId);
  const mainframeAssetsQuery = useMainframeAssets(selectedId);
  const loading = blueprintsQuery.isLoading || dataSourcesQuery.isLoading || policiesQuery.isLoading;

  const selectBlueprint = async (id: number | null) => {
    if (id === selectedId) {
      setLibraryOpened(false);
      return;
    }
    if (workspaceDirty) {
      const discard = await confirm({
        title: 'Discard unsaved DataScope changes?',
        message: 'Switching blueprints will discard unsaved profile, map, or relationship edits in this workspace.',
        okText: 'Discard and switch',
        danger: true
      });
      if (!discard) return;
    }
    setWorkspaceDirty(false);
    setSelectedId(id);
    setLibraryOpened(false);
  };

  return (
    <main className="forge-page datascope-page">
      {confirmElement}
      <Stack gap="md">
        <header className="datascope-page-header">
          <Group gap="sm" wrap="nowrap" align="flex-start">
            <span className="datascope-page-icon"><IconDatabase size={20} /></span>
            <div>
              <Group gap="xs">
                <Title order={1} size="h2">DataScope</Title>
                {selectedBlueprint ? <Badge variant="light">{selectedBlueprint.name}</Badge> : null}
              </Group>
              <Text c="dimmed" size="sm">Design governed relational subsets and copybook-driven mainframe file scopes.</Text>
            </div>
          </Group>
          <Group gap="xs" className="datascope-page-actions">
            <Button variant="subtle" leftSection={<IconHistory size={16} />} onClick={() => setHistoryOpened(true)}>
              Run history
            </Button>
            <Button variant="subtle" leftSection={<IconFolderOpen size={16} />} onClick={() => setLibraryOpened(true)}>
              Blueprints <Badge size="xs" variant="light">{blueprintsQuery.data?.length || 0}</Badge>
            </Button>
            {canManage ? <Button variant="light" leftSection={<IconPlus size={16} />} onClick={() => setCreateOpened(true)}>New blueprint</Button> : null}
            <Tooltip label="Refresh DataScope">
              <ActionIcon size={36} variant="light" aria-label="Refresh DataScope" onClick={() => void queryClient.invalidateQueries()}>
                <IconRefresh size={17} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </header>

        <QueryErrorBanner
          errors={[
            dataSourcesQuery.error,
            policiesQuery.error,
            blueprintsQuery.error,
            savedJobsQuery.error,
            profilesQuery.error,
            piiCoverageQuery.error,
            driftQuery.error,
            overridesQuery.error,
            mainframeAssetsQuery.error
          ]}
          onRetry={() => queryClient.invalidateQueries()}
          title="DataScope could not load all backend data"
        />

        {loading ? (
          <Paper className="forge-card" p="xl">
            <Group justify="center"><Loader /><Text c="dimmed">Loading DataScope workspace...</Text></Group>
          </Paper>
        ) : (
          <ErrorBoundary title="The DataScope workspace crashed">
            <SelectedBlueprintWorkspace
              key={selectedBlueprint?.id || 'none'}
              blueprint={selectedBlueprint}
              dataSources={dataSourcesQuery.data || []}
              policies={policiesQuery.data || []}
              profiles={profilesQuery.data || []}
              mainframeAssets={mainframeAssetsQuery.data || []}
              overrides={overridesQuery.data || []}
              piiCoverage={piiCoverageQuery.data}
              drift={driftQuery.data}
              savedJobs={savedJobsQuery.data || []}
              isProfilesLoading={profilesQuery.isFetching}
              isMainframeAssetsLoading={mainframeAssetsQuery.isFetching}
              isGuardrailsLoading={piiCoverageQuery.isFetching || driftQuery.isFetching}
              onOpenLibrary={() => setLibraryOpened(true)}
              onOpenCreate={canManage ? () => setCreateOpened(true) : undefined}
              onDeleted={() => {
                setWorkspaceDirty(false);
                setSelectedId(null);
              }}
              onDraftDirtyChange={setWorkspaceDirty}
            />
          </ErrorBoundary>
        )}
      </Stack>

      <Drawer opened={libraryOpened} onClose={() => setLibraryOpened(false)} position="left" size="lg" title="DataScope blueprints">
        <BlueprintList
          rows={blueprintsQuery.data || []}
          dataSources={dataSourcesQuery.data || []}
          selectedId={selectedId}
          onSelect={(id) => void selectBlueprint(id)}
          onCreate={canManage ? () => {
             if (!canManage) return;
             setLibraryOpened(false);
             setCreateOpened(true);
           } : undefined}
        />
      </Drawer>

      <Drawer opened={createOpened && canManage} onClose={() => setCreateOpened(false)} position="right" size="lg" title="Create DataScope blueprint">
        <CreateBlueprintPanel
          dataSources={dataSourcesQuery.data || []}
          onCreated={(id) => {
            setCreateOpened(false);
            void selectBlueprint(id);
          }}
        />
      </Drawer>

      <Modal opened={historyOpened} onClose={() => setHistoryOpened(false)} title="DataScope run history" fullScreen>
        <ProvisionJobMonitor datasetNames={blueprintNames} />
      </Modal>
    </main>
  );
}
