import { useEffect, useRef, useState } from 'react';

export function useWebSocket(onMessage: (msg: any) => void, enabled = true): boolean {
  const [connected, setConnected] = useState(false);
  const cbRef = useRef(onMessage);
  cbRef.current = onMessage;

  useEffect(() => {
    if (!enabled) return;
    if (window.location.protocol !== 'http:' && window.location.protocol !== 'https:') {
      return;
    }

    let ws: WebSocket | null = null;
    let timer: number | null = null;
    let disposed = false;
    let attempt = 0;

    const connect = () => {
      if (disposed) return;
      try {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${protocol}//${window.location.host}/api/ws`);
        ws.onopen = () => {
          attempt = 0;
          setConnected(true);
        };
        ws.onmessage = (event) => {
          try {
            cbRef.current(JSON.parse(event.data));
          } catch {}
        };
        ws.onclose = () => {
          setConnected(false);
          if (!disposed) {
            const delay = Math.min(10000, 1000 * Math.pow(2, Math.min(attempt, 4)));
            attempt++;
            timer = window.setTimeout(connect, delay);
          }
        };
        ws.onerror = () => {
          try {
            ws?.close();
          } catch {}
        };
      } catch {
        setConnected(false);
      }
    };

    connect();
    return () => {
      disposed = true;
      setConnected(false);
      if (timer !== null) window.clearTimeout(timer);
      try {
        ws?.close();
      } catch {}
    };
  }, [enabled]);

  return connected;
}
