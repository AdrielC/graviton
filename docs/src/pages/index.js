import React, { useEffect } from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  useEffect(() => {
    const base = (siteConfig && siteConfig.baseUrl) ? siteConfig.baseUrl : '/';
    window.location.replace(base + 'docs/');
  }, []);
  return null;
}
