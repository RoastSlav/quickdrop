module.exports = {
  content: [
    './src/main/resources/templates/**/*.html',
    './src/main/resources/static/js/**/*.js'
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: {
          light: '#0ea5e9', // sky-500
          dark: '#38bdf8'  // sky-400
        }
      }
    }
  },
  plugins: [],
};
