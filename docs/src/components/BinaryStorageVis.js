import React, { useEffect, useRef } from 'react';

export default function BinaryStorageVis() {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const W = canvas.width;
    const H = canvas.height;

    const nodes = [
      { id: 'manifest', group: 1, x: W * 0.2, y: H * 0.5 },
      { id: 'block1', group: 2, x: W * 0.45, y: H * 0.3 },
      { id: 'block2', group: 2, x: W * 0.45, y: H * 0.5 },
      { id: 'block3', group: 2, x: W * 0.45, y: H * 0.7 },
      { id: 'store1', group: 3, x: W * 0.75, y: H * 0.35 },
      { id: 'store2', group: 3, x: W * 0.75, y: H * 0.65 },
    ];
    const links = [
      ['manifest', 'block1'],
      ['manifest', 'block2'],
      ['manifest', 'block3'],
      ['block1', 'store1'],
      ['block2', 'store1'],
      ['block2', 'store2'],
      ['block3', 'store2'],
    ];

    const colorFor = g => (g === 1 ? '#00ff41' : g === 2 ? '#3fb950' : '#58d46e');

    function draw() {
      ctx.clearRect(0, 0, W, H);
      ctx.strokeStyle = '#21262d';
      ctx.lineWidth = 2;
      links.forEach(([a, b]) => {
        const na = nodes.find(n => n.id === a);
        const nb = nodes.find(n => n.id === b);
        ctx.beginPath();
        ctx.moveTo(na.x, na.y);
        ctx.lineTo(nb.x, nb.y);
        ctx.stroke();
      });
      nodes.forEach(n => {
        ctx.fillStyle = colorFor(n.group);
        ctx.beginPath();
        ctx.arc(n.x, n.y, 10, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = '#e6edf3';
        ctx.font = '12px sans-serif';
        ctx.fillText(n.id, n.x + 12, n.y + 4);
      });
    }

    draw();
  }, []);

  return (
    <div style={{ height: 520, background: '#0d1117', padding: '10px', border: '1px solid #21262d' }}>
      <canvas ref={canvasRef} width={900} height={500} style={{ width: '100%', maxWidth: '100%' }} />
    </div>
  );
}