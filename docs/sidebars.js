const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "graviton",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        {
          type: "category",
          label: "Getting Started",
          collapsed: false,
          items: [
            {
              type: "doc",
              id: "getting-started/quick-start"
            }
          ]
        }
      ]
    }
  ]
};

module.exports = sidebars;
