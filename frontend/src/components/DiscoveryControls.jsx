import React, { useState } from 'react';
import { scanSubnet } from '../api/client';

const inputStyle = {
  background: 'var(--bg-panel-raised)',
  border: '1px solid var(--border-subtle)',
  borderRadius: 6,
  color: 'var(--text-primary)',
  fontFamily: 'var(--font-mono)',
  fontSize: 12,
  padding: '7px 10px',
  outline: 'none',
};

const labelStyle = {
  fontSize: 10,
  color: 'var(--text-tertiary)',
  textTransform: 'uppercase',
  letterSpacing: 0.4,
  marginBottom: 4,
  display: 'block',
};

export default function DiscoveryControls({ onScanComplete, onRefresh }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    subnet: '192.168.100.0/24',
    vendor: 'JUNIPER',
    sshUsername: '',
    sshPassword: '',
    snmpCommunity: '',
  });
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const handleChange = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setScanning(true);
    setError(null);
    setResult(null);
    try {
      const response = await scanSubnet({
        ...form,
        snmpCommunity: form.snmpCommunity || undefined,
        autoStartDiscovery: true,
      });
      setResult(response);
      onScanComplete?.(response);
    } catch (err) {
      setError(err.response?.data?.error || err.message || 'Scanare esuata');
    } finally {
      setScanning(false);
    }
  };

  return (
    <div style={{
      position: 'absolute',
      top: 16,
      left: 16,
      zIndex: 10,
      background: 'var(--bg-panel)',
      border: '1px solid var(--border-subtle)',
      borderRadius: 10,
      boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
      width: open ? 320 : 'auto',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px' }}>
        <button
          onClick={() => setOpen(!open)}
          style={{
            background: 'var(--accent-active)',
            color: '#0B0E14',
            border: 'none',
            borderRadius: 6,
            padding: '7px 14px',
            fontSize: 12,
            fontWeight: 600,
            cursor: 'pointer',
          }}
        >
          {open ? 'Inchide' : '+ Scaneaza subnet'}
        </button>
        <button
          onClick={onRefresh}
          title="Reincarca topologia"
          style={{
            background: 'transparent',
            border: '1px solid var(--border-subtle)',
            borderRadius: 6,
            color: 'var(--text-secondary)',
            padding: '7px 10px',
            fontSize: 12,
            cursor: 'pointer',
          }}
        >
          ↻
        </button>
      </div>

      {open && (
        <form onSubmit={handleSubmit} style={{ padding: '0 14px 14px 14px' }}>
          <div style={{ marginBottom: 10 }}>
            <label style={labelStyle}>Subnet (CIDR)</label>
            <input
              style={{ ...inputStyle, width: '100%' }}
              value={form.subnet}
              onChange={handleChange('subnet')}
              placeholder="192.168.100.0/24"
              required
            />
          </div>

          <div style={{ marginBottom: 10 }}>
            <label style={labelStyle}>Vendor</label>
            <select
              style={{ ...inputStyle, width: '100%' }}
              value={form.vendor}
              onChange={handleChange('vendor')}
            >
              <option value="JUNIPER">Juniper</option>
              <option value="ARISTA">Arista</option>
            </select>
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>SSH user</label>
              <input
                style={{ ...inputStyle, width: '100%' }}
                value={form.sshUsername}
                onChange={handleChange('sshUsername')}
                required
              />
            </div>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>SSH parola</label>
              <input
                type="password"
                style={{ ...inputStyle, width: '100%' }}
                value={form.sshPassword}
                onChange={handleChange('sshPassword')}
                required
              />
            </div>
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>SNMP community (optional)</label>
            <input
              style={{ ...inputStyle, width: '100%' }}
              value={form.snmpCommunity}
              onChange={handleChange('snmpCommunity')}
              placeholder="public (implicit)"
            />
          </div>

          <button
            type="submit"
            disabled={scanning}
            style={{
              width: '100%',
              background: scanning ? 'var(--border-strong)' : 'var(--accent-info)',
              color: '#fff',
              border: 'none',
              borderRadius: 6,
              padding: '9px 0',
              fontSize: 12,
              fontWeight: 600,
              cursor: scanning ? 'default' : 'pointer',
            }}
          >
            {scanning ? 'Se scaneaza...' : 'Porneste scanarea + discovery'}
          </button>

          {error && (
            <div style={{ marginTop: 10, fontSize: 11, color: 'var(--accent-error)' }}>
              {error}
            </div>
          )}

          {result && (
            <div style={{ marginTop: 10, fontSize: 11, color: 'var(--accent-active)' }}>
              Gasite {result.liveHostsFound} device-uri. Discovery {result.discoveryStarted ? 'pornit.' : 'nu a pornit.'}
            </div>
          )}
        </form>
      )}
    </div>
  );
}
