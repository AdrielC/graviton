# 🌌 Graviton Documentation

> **Matrix-themed, cyberpunk documentation with interactive effects**

## ✨ What's New

Your Graviton docs just got a **MAJOR UPGRADE** with:

### 🎨 Visual Effects
- **Matrix Rain Background**: Subtle falling code effect in the background
- **Particle Cursor Trail**: Green glowing particles follow your mouse
- **Neon Glow Effects**: All UI elements have cyberpunk-style glow
- **Smooth Scroll Animations**: Content fades in as you scroll
- **Scanline Overlay**: Retro CRT monitor effect

### 🎯 Enhanced Homepage
- Beautiful hero section with animated logo
- 6 feature cards with icons and descriptions
- Interactive card grid with hover effects
- Quick start commands and guide links
- Modern layout with gradients

### 🚀 Interactive Features
- **Glitch Effect**: Headings glitch on hover
- **Pulsing Borders**: Important callouts pulse with neon glow
- **Code Block Effects**: Enhanced syntax highlighting with glow
- **Animated Navigation**: Loading bar animation
- **Icon Animations**: Icons rotate and glow on hover

### 🎨 Theme Customization
- **Vaporwave Color Palette**: Matrix green (#00ff41) + cyan + purple + pink
- **Custom Scrollbar**: Neon green with glow effect
- **Dark Terminal Theme**: True black background (#0a0e14)
- **Gradient Text**: Hero titles use multi-color gradients
- **Glass Morphism**: Frosted glass effect on navigation

### 📱 Modern UX
- Staggered animations for feature cards
- Smooth page transitions
- Enhanced mobile responsiveness
- Better search interface
- Improved navigation labels with emojis

## 🚀 Running the Docs

```bash
# Install dependencies
cd docs
npm install

# Start dev server with hot reload
npm run docs:dev

# Build for production
npm run docs:build

# Preview production build
npm run docs:preview
```

Visit `http://localhost:5173/` (append your `DOCS_BASE` if you set one) to see the magic! ✨

## 🎨 Customization

### Colors
Edit `docs/.vitepress/theme/custom.css` to customize colors:
- `--vp-c-brand-1`: Main green color
- `--vp-c-cyan`: Accent cyan
- `--vp-c-purple`: Accent purple
- `--vp-c-pink`: Accent pink

### Animations
Toggle effects in `docs/.vitepress/theme/index.ts`:
- `createMatrixRain()`: Matrix background
- `initParticleTrail()`: Cursor particles
- `initScrollAnimations()`: Scroll reveals

### Content
- Homepage: `docs/index.md`
- Config: `docs/.vitepress/config.ts`
- Custom CSS: `docs/.vitepress/theme/custom.css`

## 🎮 Easter Eggs

Try these:
- Hover over headings for glitch effect
- Watch the navigation loading bar
- Hover over feature cards to see icons rotate
- Move your cursor to see particle trails
- Scroll to reveal content animations

## 🛠️ Tech Stack

- **VitePress**: Modern static site generator
- **Vue 3**: Reactive framework
- **TypeScript**: Type-safe JavaScript
- **Canvas API**: For particle effects
- **CSS Animations**: Smooth, performant effects
- **Intersection Observer**: Scroll-triggered animations

## 📚 Features by Section

### Navigation
- ✨ Glowing logo
- 🎭 Animated loading bar
- 🔍 Enhanced search with neon focus
- 📱 Mobile-friendly hamburger menu

### Homepage
- 🎯 Animated hero with gradient text
- ⚡ 6 feature cards with staggered fade-in
- 📊 Stats and quick links grid
- 💫 Particle effects on scroll

### Documentation Pages
- 📖 Smooth reveal animations
- 🎨 Syntax-highlighted code blocks
- 💡 Neon-bordered callouts
- 🔗 Glowing link underlines
- 📑 Enhanced table of contents

### Code Blocks
- ✨ Glow effect on hover
- 💚 Matrix green theme
- 📋 Copy button with animation
- 🖥️ Terminal-style prompt for bash

## 🐛 Performance

Despite all the effects, the site remains **blazingly fast**:
- Lazy-loaded animations
- GPU-accelerated transforms
- Minimal JavaScript overhead
- Optimized Canvas rendering
- Efficient CSS animations

## 🌐 Browser Support

Works perfectly in:
- ✅ Chrome/Edge (latest)
- ✅ Firefox (latest)
- ✅ Safari (latest)
- ✅ Mobile browsers

## 🤝 Contributing

Want to make the docs even cooler? PRs welcome!

Ideas:
- Add Konami code easter egg
- Implement particle physics
- Add sound effects (toggle)
- Create theme variations
- Add 3D effects

---

**Built with 💚 and lots of neon** • [Graviton](https://github.com/AdrielC/graviton)
