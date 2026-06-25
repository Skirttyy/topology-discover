import React, { useState } from 'react';
import { scanSubnet, stopDiscovery, resetTopology } from '../api/client';

const inputStyle = {
  background: '#1A1F2B',
  border: '1px solid #252A35',
  borderRadius: 6,
  color: '#E4E7EB',
  fontFamily: "'JetBrains Mono', monospace",
  fontSize: 12,
  padding: '7px 10px',
  outline: 'none',
  width: '100%',
  boxSizing: 'border-box',
};

const labelStyle = {
  fontSize: 10,
  color: '#5A6275',
  textTransform: 'uppercase',
  letterSpacing: 0.5,
  marginBottom: 4,
  display: 'block',
};

export default function DiscoveryControls({ onScanComplete, onRefresh, isRunning }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    subnet: '192.168.1.0/24',
    vendor: 'JUNIPER',
    sshUsername: '',
    sshPassword: '',
    snmpCommunity: '',
  });
  const [scanning, setScanning] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const handleStop = async () => {
    setStopping(true);
    try { await stopDiscovery(); } catch (e) { /* ignoram */ }
    finally { setStopping(false); }
  };

  const handleReset = async () => {
    if (!confirmReset) { setConfirmReset(true); return; }
    setResetting(true);
    setConfirmReset(false);
    setResult(null);
    setError(null);
    try {
      await resetTopology();
      onRefresh?.();
    } catch (err) {
      setError(err.response?.data?.error || err.message || 'Eroare la reset');
    } finally {
      setResetting(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setScanning(true);
    setError(null);
    setResult(null);
    try {
      const res = await scanSubnet({
        ...form,
        snmpCommunity: form.snmpCommunity || undefined,
        autoStartDiscovery: true,
      });
      setResult(res);
      onScanComplete?.();
    } catch (err) {
      setError(err.response?.data?.error || err.message || 'Eroare');
    } finally {
      setScanning(false);
    }
  };

  return (
    <div style={{
      position: 'absolute', top: 16, left: 16, zIndex: 10,
      background: '#131720',
      border: '1px solid #252A35',
      borderRadius: 10,
      boxShadow: '0 4px 24px rgba(0,0,0,0.5)',
      minWidth: 'max-content',
      width: open ? 320 : 'auto',
      transition: 'width 0.2s ease',
      overflow: 'hidden',
    }}>
      {/* toolbar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px' }}>
        <button
          onClick={() => { setOpen(!open); setConfirmReset(false); setError(null); setResult(null); }}
          style={{
            background: '#3DDC84', color: '#0B0E14',
            border: 'none', borderRadius: 6,
            padding: '6px 12px', fontSize: 12, fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          {open ? '✕ Inchide' : '+ Subnet scan'}
        </button>

        {/* Stop - vizibil doar cand discovery ruleaza */}
        {isRunning && (
          <button
            onClick={handleStop}
            disabled={stopping}
            title="Opreste discovery-ul in curs"
            style={{
              background: stopping ? '#252A35' : 'rgba(242,84,91,0.12)',
              border: '1px solid #F2545B',
              borderRadius: 6, color: stopping ? '#5A6275' : '#F2545B',
              padding: '6px 10px', fontSize: 11, fontWeight: 700,
              cursor: stopping ? 'default' : 'pointer',
            }}
          >
            {stopping ? '...' : '⏹ Stop'}
          </button>
        )}

        <button
          onClick={onRefresh}
          title="Reincarca topologia"
          style={{
            background: 'transparent',
            border: '1px solid #252A35',
            borderRadius: 6, color: '#8B93A3',
            padding: '6px 10px', fontSize: 14, cursor: 'pointer',
          }}
        >↻</button>

        {/* Reset - cu confirmare dubla */}
        <button
          onClick={handleReset}
          disabled={resetting}
          title={confirmReset ? 'Click din nou pentru confirmare' : 'Sterge toata topologia si porneste de la capat'}
          style={{
            background: confirmReset ? '#F2545B' : 'transparent',
            border: `1px solid ${confirmReset ? '#F2545B' : '#252A35'}`,
            borderRadius: 6,
            color: confirmReset ? '#fff' : '#8B93A3',
            padding: '6px 10px', fontSize: 11, fontWeight: confirmReset ? 700 : 400,
            cursor: resetting ? 'default' : 'pointer',
            transition: 'all 0.15s',
          }}
          onBlur={() => setConfirmReset(false)}
        >
          {resetting ? '...' : confirmReset ? '⚠ Confirma reset' : '⊘ Reset'}
        </button>
      </div>

      {/* form */}
      {open && (
        <form onSubmit={handleSubmit} style={{ padding: '4px 14px 14px' }}>
          <div style={{ marginBottom: 10 }}>
            <label style={labelStyle}>Subnet (CIDR)</label>
            <input style={inputStyle} value={form.subnet} onChange={set('subnet')} required
              placeholder="192.168.1.0/24" />
          </div>

          <div style={{ marginBottom: 10 }}>
            <label style={labelStyle}>Vendor</label>
            <select style={inputStyle} value={form.vendor} onChange={set('vendor')}>
              <option value="JUNIPER">Juniper</option>
              <option value="ARISTA">Arista</option>
              <option value="UNKNOWN">Auto-detect</option>
            </select>
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>SSH user</label>
              <input style={inputStyle} value={form.sshUsername} onChange={set('sshUsername')} required />
            </div>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>SSH parola</label>
              <input type="password" style={inputStyle} value={form.sshPassword} onChange={set('sshPassword')} required />
            </div>
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>SNMP community (implicit: public)</label>
            <input style={inputStyle} value={form.snmpCommunity} onChange={set('snmpCommunity')}
              placeholder="public" />
          </div>

          <button type="submit" disabled={scanning} style={{
            width: '100%', border: 'none', borderRadius: 6,
            padding: '9px 0', fontSize: 12, fontWeight: 700,
            background: scanning ? '#252A35' : '#4D9DF2',
            color: scanning ? '#5A6275' : '#fff',
            cursor: scanning ? 'default' : 'pointer',
            transition: 'all 0.2s',
          }}>
            {scanning ? '⟳ Se scaneaza...' : 'Porneste discovery'}
          </button>

          {error && (
            <div style={{ marginTop: 10, fontSize: 11, color: '#F2545B',
              background: 'rgba(242,84,91,0.08)', borderRadius: 4, padding: '6px 8px' }}>
              {error}
            </div>
          )}
          {result && (
            <div style={{ marginTop: 10, fontSize: 11, color: '#3DDC84' }}>
              {result.liveHostsFound} device-uri gasite.
              {result.discoveryStarted ? ' Discovery pornit.' : ''}
            </div>
          )}
        </form>
      )}
    </div>
  );
}
