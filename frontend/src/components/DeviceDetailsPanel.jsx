import React, { useEffect, useState } from 'react';
import { getDevice } from '../api/client';

const STATUS_LABEL = {
  ACTIVE: 'Activ',
  DISCOVERED: 'Descoperit',
  CONFIGURING: 'Configurare in curs',
  POLLING: 'Interogare in curs',
  UNREACHABLE: 'Inaccesibil',
  ERROR: 'Eroare',
};

const STATUS_COLOR = {
  ACTIVE: 'var(--accent-active)',
  DISCOVERED: 'var(--accent-neutral)',
  CONFIGURING: 'var(--accent-info)',
  POLLING: 'var(--accent-info)',
  UNREACHABLE: 'var(--accent-warning)',
  ERROR: 'var(--accent-error)',
};

export default function DeviceDetailsPanel({ deviceId, onClose }) {
  const [device, setDevice] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!deviceId) return;
    setLoading(true);
    setError(null);
    getDevice(deviceId)
      .then(setDevice)
      .catch((e) => setError(e.message || 'Eroare la incarcare'))
      .finally(() => setLoading(false));
  }, [deviceId]);

  if (!deviceId) return null;

  return (
    <aside
      style={{
        position: 'absolute',
        top: 0,
        right: 0,
        bottom: 0,
        width: 360,
        background: 'var(--bg-panel)',
        borderLeft: '1px solid var(--border-subtle)',
        display: 'flex',
        flexDirection: 'column',
        zIndex: 10,
      }}
    >
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '16px 20px',
        borderBottom: '1px solid var(--border-subtle)',
      }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)', letterSpacing: 0.3 }}>
          DETALII DEVICE
        </span>
        <button
          onClick={onClose}
          aria-label="Inchide panoul"
          style={{
            background: 'transparent',
            border: 'none',
            color: 'var(--text-tertiary)',
            fontSize: 18,
            cursor: 'pointer',
            lineHeight: 1,
            padding: 4,
          }}
        >
          ×
        </button>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: 20 }}>
        {loading && (
          <div style={{ color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)', fontSize: 13 }}>
            Se incarca...
          </div>
        )}

        {error && (
          <div style={{ color: 'var(--accent-error)', fontSize: 13 }}>
            Nu am putut incarca detaliile: {error}
          </div>
        )}

        {device && !loading && (
          <>
            <h2 style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 18,
              fontWeight: 700,
              margin: '0 0 4px 0',
              wordBreak: 'break-word',
            }}>
              {device.hostname || device.managementIp}
            </h2>
            <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16 }}>
              {device.managementIp}
            </div>

            <div style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '4px 10px',
              borderRadius: 99,
              background: 'var(--bg-panel-raised)',
              border: '1px solid var(--border-subtle)',
              marginBottom: 20,
            }}>
              <span style={{
                width: 6, height: 6, borderRadius: '50%',
                background: STATUS_COLOR[device.status] || 'var(--text-tertiary)',
              }} />
              <span style={{ fontSize: 12, color: 'var(--text-primary)' }}>
                {STATUS_LABEL[device.status] || device.status}
              </span>
            </div>

            <Section title="Identificare">
              <Field label="Vendor" value={device.vendor} />
              <Field label="Model" value={device.model} />
              <Field label="Versiune OS" value={device.osVersion} />
              <Field label="Serial" value={device.serialNumber} />
            </Section>

            {device.sysDescr && (
              <Section title="System Description (SNMP)">
                <div style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--text-secondary)',
                  background: 'var(--bg-panel-raised)',
                  border: '1px solid var(--border-subtle)',
                  borderRadius: 6,
                  padding: 10,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}>
                  {device.sysDescr}
                </div>
              </Section>
            )}

            <Section title="Discovery">
              <Field label="Tip" value={device.seedDevice ? 'Seed (adaugat manual/scan)' : 'Descoperit din topologie'} />
              <Field label="Prima descoperire" value={formatDate(device.firstDiscoveredAt)} />
              <Field label="Ultima interogare" value={formatDate(device.lastPolledAt)} />
            </Section>

            {device.lastError && (
              <Section title="Ultima eroare">
                <div style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 12,
                  color: 'var(--accent-error)',
                  background: 'rgba(242, 84, 91, 0.08)',
                  border: '1px solid rgba(242, 84, 91, 0.25)',
                  borderRadius: 6,
                  padding: 10,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}>
                  {device.lastError}
                </div>
              </Section>
            )}

            <Section title={`Interfete (${device.interfaces?.length || 0})`}>
              {(!device.interfaces || device.interfaces.length === 0) && (
                <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
                  Nicio interfata descoperita inca.
                </div>
              )}
              {device.interfaces?.map((iface) => (
                <div
                  key={iface.name}
                  style={{
                    border: '1px solid var(--border-subtle)',
                    borderRadius: 6,
                    padding: 10,
                    marginBottom: 8,
                    background: 'var(--bg-panel-raised)',
                  }}
                >
                  <div style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6,
                  }}>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 600 }}>
                      {iface.name}
                    </span>
                    <span style={{
                      fontSize: 10,
                      padding: '2px 6px',
                      borderRadius: 4,
                      background: iface.operStatus === 'up'
                        ? 'rgba(61, 220, 132, 0.12)'
                        : 'rgba(90, 98, 117, 0.2)',
                      color: iface.operStatus === 'up' ? 'var(--accent-active)' : 'var(--text-tertiary)',
                    }}>
                      {iface.operStatus || 'necunoscut'}
                    </span>
                  </div>
                  {iface.ipAddress && (
                    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-secondary)' }}>
                      {iface.ipAddress}{iface.prefixLength ? `/${iface.prefixLength}` : ''}
                    </div>
                  )}
                  {iface.macAddress && (
                    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-tertiary)' }}>
                      {iface.macAddress}
                    </div>
                  )}
                  {iface.speedMbps && (
                    <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 2 }}>
                      {iface.speedMbps} Mbps
                    </div>
                  )}
                </div>
              ))}
            </Section>
          </>
        )}
      </div>
    </aside>
  );
}

function Section({ title, children }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <div style={{
        fontSize: 11,
        fontWeight: 600,
        color: 'var(--text-tertiary)',
        textTransform: 'uppercase',
        letterSpacing: 0.5,
        marginBottom: 10,
      }}>
        {title}
      </div>
      {children}
    </div>
  );
}

function Field({ label, value }) {
  if (!value) return null;
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', fontSize: 13 }}>
      <span style={{ color: 'var(--text-tertiary)' }}>{label}</span>
      <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-primary)', textAlign: 'right' }}>
        {value}
      </span>
    </div>
  );
}

function formatDate(isoString) {
  if (!isoString) return null;
  try {
    return new Date(isoString).toLocaleString('ro-RO');
  } catch {
    return isoString;
  }
}
