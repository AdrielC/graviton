import React from 'react';
import Layout from '@theme/Layout';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

export default function ApiPage() {
  const { siteConfig } = useDocusaurusContext();
  const base = (siteConfig && siteConfig.baseUrl) ? siteConfig.baseUrl : '/';
  const apiUrl = base.replace(/\/?$/, '/') + 'api/';
  return (
    <Layout title="API Reference" description="ScalaDoc API Reference">
      <div style={{height: 'calc(100vh - 120px)'}}>
        <iframe src={apiUrl} title="ScalaDoc" style={{width:'100%', height:'100%', border:0}} />
      </div>
    </Layout>
  );
}

import React from 'react';
import Layout from '@theme/Layout';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

export default function ApiPage() {
  const { siteConfig } = useDocusaurusContext();
  const base = (siteConfig && siteConfig.baseUrl) ? siteConfig.baseUrl : '/';
  const apiUrl = base + 'api/';

  return (
    <Layout title="API Reference" description="ScalaDoc API Reference">
      <div style={{height: 'calc(100vh - 120px)'}}>
        <iframe src={apiUrl} title="ScalaDoc" style={{width:'100%', height:'100%', border:0}} />
      </div>
    </Layout>
  );
}


