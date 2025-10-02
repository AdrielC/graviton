
import React from 'react';
import Layout from '@theme/Layout';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

export default function ApiPage() {
  const { siteConfig } = useDocusaurusContext();
  const path = useBaseUrl('api/index.html');
  const origin = (typeof window !== 'undefined' && window.location && window.location.origin) ? window.location.origin : (siteConfig && siteConfig.url) || '';
  const apiUrl = origin + path;

  return (
    <Layout title="API Reference" description="ScalaDoc API Reference">
      <div style={{height: 'calc(100vh - 120px)'}}>
        <iframe
          src={apiUrl}
          title="ScalaDoc"
          style={{width:'100%', height:'100%', border:0}}
          sandbox="allow-same-origin allow-scripts allow-forms"
          referrerPolicy="no-referrer"
        />
      </div>
    </Layout>
  );
}


