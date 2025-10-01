import React from 'react';
import Layout from '@theme/Layout';
import BinaryStorageVis from '../components/BinaryStorageVis';

export default function VisPage() {
  return (
    <Layout title="Visualization" description="Binary Storage Visualization">
      <div style={{ padding: '1rem' }}>
        <h1>Binary Storage Visualization</h1>
        <p>Blocks, manifests, and stores (demo).</p>
        <BinaryStorageVis />
      </div>
    </Layout>
  );
}


