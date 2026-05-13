import { Navigate, Route, Routes } from 'react-router-dom';
import AuthPage from './pages/AuthPage';
import CharactersPage from './pages/CharactersPage';
import ChatPage from './pages/ChatPage';

export default function App() {
  return (
    <div className="app-container">
      <header className="app-header">
        <h1>SKU SW Temporary Test Frontend</h1>
      </header>
      <main>
        <Routes>
          <Route path="/auth" element={<AuthPage />} />
          <Route path="/characters" element={<CharactersPage />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="*" element={<Navigate to="/auth" replace />} />
        </Routes>
      </main>
    </div>
  );
}
