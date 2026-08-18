'use client';

import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Container,
  CopyButton,
  Divider,
  Group,
  Loader,
  NumberInput,
  Paper,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconArrowLeft,
  IconBookmark,
  IconCheck,
  IconCopy,
  IconPlus,
  IconRefresh,
  IconShieldCheck,
  IconSparkles,
  IconTrash,
  IconWand
} from '@tabler/icons-react';

import { usePermissions } from '@/lib/use-permissions';
import { useMyRequests, useRecipes, useTdMutations } from './hooks';
import type { TdRequestView } from './types';

const EXAMPLES = [
  'Create a customer with a DDA of $100 and an active mortgage',
  'A customer with a checking account',
  'A customer with an active mortgage and a dormant DDA'
];

const STATUS_COLOR: Record<string, string> = {
  PLANNED: 'gray',
  READY: 'teal',
  FAILED: 'red',
  TORN_DOWN: 'dark'
};

export function TestDataPage() {
  const perms = usePermissions();
  const canRun = perms.can('provision.run');
  const recipesQuery = useRecipes();
  const myRequests = useMyRequests();
  const m = useTdMutations();

  const [step, setStep] = useState<'intake' | 'plan' | 'receipt'>('intake');
  const [text, setText] = useState('');
  const [environment, setEnvironment] = useState<string | null>('SIT');
  const [quantity, setQuantity] = useState<number | string>(1);
  const [purpose, setPurpose] = useState('');
  const [current, setCurrent] = useState<TdRequestView | null>(null);

  const notifyErr = (title: string, e: unknown) =>
    notifications.show({ color: 'red', title, message: (e as Error).message });

  const submit = async () => {
    try {
      const res = await m.create.mutateAsync({
        request: text,
        environment: environment || undefined,
        quantity: typeof quantity === 'number' ? quantity : undefined,
        purpose: purpose || undefined
      });
      setCurrent(res);
      setStep('plan');
    } catch (e) {
      notifyErr('Could not interpret the request', e);
    }
  };

  const confirm = async () => {
    if (!current) return;
    try {
      const res = await m.confirm.mutateAsync(current.id);
      setCurrent(res);
      setStep('receipt');
      notifications.show({ color: 'teal', message: 'Provisioned ✓' });
    } catch (e) {
      notifyErr('Provisioning failed', e);
    }
  };

  const startOver = () => {
    setCurrent(null);
    setText('');
    setPurpose('');
    setQuantity(1);
    setStep('intake');
  };

  const plan = current?.plan;
  const receipt = current?.receipt;

  return (
    <Container size="lg" py="md">
      <Group gap="xs" mb={4}>
        <IconWand size={22} />
        <Title order={2}>Request test data</Title>
      </Group>
      <Text c="dimmed" size="sm" mb="lg">
        Describe what you need in plain language. We&apos;ll show you exactly what will be created, then
        provision it and tell you where to find it — no tables, blueprints, or policies.
      </Text>

      {/* ---------------- INTAKE ---------------- */}
      {step === 'intake' && (
        <Paper withBorder p="lg" radius="md">
          <Textarea
            label="What data do you need?"
            placeholder="e.g. Create a customer with a DDA of $100 and an active mortgage"
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
            autosize
            minRows={3}
            maxRows={8}
          />
          <Group gap={6} mt="xs">
            <Text size="xs" c="dimmed">
              Try:
            </Text>
            {EXAMPLES.map((ex) => (
              <Badge
                key={ex}
                variant="light"
                style={{ cursor: 'pointer' }}
                leftSection={<IconSparkles size={11} />}
                onClick={() => setText(ex)}
              >
                {ex.length > 42 ? ex.slice(0, 40) + '…' : ex}
              </Badge>
            ))}
          </Group>
          <Group mt="md" align="flex-end">
            <Select
              label="Environment"
              data={['SIT', 'UAT', 'DEV']}
              value={environment}
              onChange={setEnvironment}
              w={120}
            />
            <NumberInput label="How many" value={quantity} onChange={setQuantity} min={1} max={50} w={110} />
            <TextInput
              label="For (optional)"
              placeholder="TC-1234"
              value={purpose}
              onChange={(e) => setPurpose(e.currentTarget.value)}
              w={180}
            />
            <div style={{ flex: 1 }} />
            <Button
              rightSection={<IconArrowLeft size={16} style={{ transform: 'rotate(180deg)' }} />}
              loading={m.create.isPending}
              disabled={!text.trim()}
              onClick={submit}
            >
              Preview what I&apos;ll get
            </Button>
          </Group>
        </Paper>
      )}

      {/* ---------------- PLAN ---------------- */}
      {step === 'plan' && plan && (
        <Paper withBorder p="lg" radius="md">
          <Group justify="space-between" mb="xs">
            <Text fw={700}>{plan.summary}</Text>
            <Badge color="teal" variant="light" leftSection={<IconShieldCheck size={12} />}>
              {plan.safety.dataOrigin === 'SYNTHETIC' ? 'Synthetic & masked-safe' : plan.safety.dataOrigin}
            </Badge>
          </Group>
          <Text size="xs" c="dimmed" mb="md">
            Target {plan.environment} · ~{plan.estimatedSeconds}s ·{' '}
            {plan.safety.approvalRequired ? 'approval required' : 'no approval needed'}
          </Text>

          <Stack gap="xs">
            {plan.assets.map((a, i) => (
              <Card key={i} withBorder padding="sm" radius="sm">
                <Group justify="space-between">
                  <Group gap="xs">
                    <IconCheck size={16} color="var(--mantine-color-teal-6)" />
                    <Text fw={600}>{a.name}</Text>
                    {a.linkedTo && (
                      <Badge size="xs" variant="light" color="gray">
                        linked to {a.linkedTo}
                      </Badge>
                    )}
                  </Group>
                  <Group gap={6}>
                    {Object.entries(a.attributes).map(([k, v]) => (
                      <Badge key={k} variant="outline" size="sm">
                        {k}: {v}
                      </Badge>
                    ))}
                  </Group>
                </Group>
              </Card>
            ))}
          </Stack>

          {plan.openQuestions.length > 0 &&
            plan.openQuestions.map((q, i) => (
              <Alert key={i} color="orange" mt="sm" p="xs">
                <Text size="xs">{q}</Text>
              </Alert>
            ))}

          <Group justify="space-between" mt="lg">
            <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} onClick={() => setStep('intake')}>
              Edit request
            </Button>
            <Tooltip label={canRun ? '' : 'Requires provision.run'} disabled={canRun}>
              <Button
                leftSection={<IconCheck size={16} />}
                loading={m.confirm.isPending}
                disabled={!canRun || plan.assets.length === 0}
                onClick={confirm}
              >
                Confirm &amp; create
              </Button>
            </Tooltip>
          </Group>
        </Paper>
      )}

      {/* ---------------- RECEIPT ---------------- */}
      {step === 'receipt' && receipt && (
        <Paper withBorder p="lg" radius="md">
          <Group justify="space-between" mb="xs">
            <Group gap="xs">
              <Badge color="teal" size="lg" leftSection={<IconCheck size={13} />}>
                Ready
              </Badge>
              <Text fw={700}>{receipt.summary}</Text>
            </Group>
            <Text size="xs" c="dimmed">
              REQ-{receipt.requestId} · {receipt.createdBy} · {receipt.environment}
            </Text>
          </Group>

          <Table striped withRowBorders={false} verticalSpacing="xs" mt="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>What</Table.Th>
                <Table.Th>ID</Table.Th>
                <Table.Th>Details</Table.Th>
                <Table.Th>Linked to</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {receipt.provisioned.map((o) => (
                <Table.Tr key={o.id}>
                  <Table.Td>
                    <Text fw={600} size="sm">
                      {o.type}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {o.label}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light">{o.id}</Badge>
                  </Table.Td>
                  <Table.Td>
                    {Object.entries(o.attributes).map(([k, v]) => (
                      <Text key={k} size="xs">
                        <b>{k}</b> {v}
                      </Text>
                    ))}
                  </Table.Td>
                  <Table.Td>{o.linkedTo ? <Badge size="xs" color="gray" variant="light">{o.linkedTo}</Badge> : '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>

          <Divider my="sm" />
          <Group justify="space-between" align="flex-start">
            <div>
              <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
                How to find it
              </Text>
              <Group gap={6}>
                <Text size="sm">
                  {receipt.howToAccess.environment} · <code>{receipt.howToAccess.find}</code>
                </Text>
                <CopyButton value={receipt.howToAccess.find}>
                  {({ copied, copy }) => (
                    <Tooltip label={copied ? 'Copied' : 'Copy'}>
                      <Button size="compact-xs" variant="subtle" leftSection={<IconCopy size={12} />} onClick={copy}>
                        {copied ? 'Copied' : 'Copy'}
                      </Button>
                    </Tooltip>
                  )}
                </CopyButton>
              </Group>
              <Text size="xs" c="dimmed" mt={4}>
                {receipt.howToAccess.note} · audit {receipt.auditRef}
              </Text>
            </div>
            <Badge color="teal" variant="light" leftSection={<IconShieldCheck size={12} />}>
              masked & synthetic
            </Badge>
          </Group>

          <Group mt="lg" gap="xs">
            <Button leftSection={<IconPlus size={16} />} onClick={startOver}>
              Provision more like this
            </Button>
            <Button
              variant="light"
              leftSection={<IconBookmark size={16} />}
              onClick={() =>
                m.reserve.mutateAsync({ id: receipt.requestId, purpose: purpose || undefined })
                  .then(() => notifications.show({ color: 'teal', message: 'Reserved' }))
                  .catch((e) => notifyErr('Reserve failed', e))
              }
            >
              Reserve
            </Button>
            <Button
              variant="light"
              color="red"
              leftSection={<IconTrash size={16} />}
              onClick={() =>
                m.teardown.mutateAsync(receipt.requestId)
                  .then(() => { notifications.show({ color: 'teal', message: 'Torn down' }); startOver(); })
                  .catch((e) => notifyErr('Teardown failed', e))
              }
            >
              Tear down
            </Button>
          </Group>
        </Paper>
      )}

      {/* ---------------- MY REQUESTS ---------------- */}
      <Group justify="space-between" mt="xl" mb="xs">
        <Title order={4}>My requests</Title>
        <Button size="compact-xs" variant="subtle" leftSection={<IconRefresh size={14} />} onClick={() => myRequests.refetch()}>
          Refresh
        </Button>
      </Group>
      <Paper withBorder radius="md" p="xs">
        {myRequests.isLoading ? (
          <Loader size="sm" />
        ) : (myRequests.data?.length ?? 0) === 0 ? (
          <Text size="sm" c="dimmed" p="sm">
            No requests yet — describe what you need above.
          </Text>
        ) : (
          <Table verticalSpacing="xs" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Request</Table.Th>
                <Table.Th>Env</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>When</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {myRequests.data!.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <Text size="sm" lineClamp={1}>{r.requestText}</Text>
                    {r.summary && <Text size="xs" c="dimmed" lineClamp={1}>{r.summary}</Text>}
                  </Table.Td>
                  <Table.Td>{r.environment}</Table.Td>
                  <Table.Td>
                    <Badge size="sm" color={STATUS_COLOR[r.status] || 'gray'}>{r.status}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">{new Date(r.createdAt).toLocaleString()}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} justify="flex-end">
                      {r.status === 'READY' && (
                        <Button size="compact-xs" variant="subtle" color="red"
                          onClick={() => m.teardown.mutateAsync(r.id).catch((e) => notifyErr('Teardown failed', e))}>
                          Tear down
                        </Button>
                      )}
                      <Button size="compact-xs" variant="subtle" color="gray"
                        onClick={() => m.remove.mutateAsync(r.id).catch((e) => notifyErr('Delete failed', e))}>
                        Delete
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Paper>
    </Container>
  );
}
