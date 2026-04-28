import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { loginEmail, registerEmail } from '../lib/api';
import { saveLogin } from '../lib/storage';

export default function AuthPage() {
  const navigate = useNavigate();

  const [registerForm, setRegisterForm] = useState({
    name: '',
    email: '',
    password: '',
    passwordConfirm: '',
  });
  const [loginForm, setLoginForm] = useState({ email: '', password: '' });
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);

  const onRegister = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setMessage('');
    try {
      const res = await registerEmail(registerForm);
      setMessage(`회원가입 성공: ${res.message}`);
    } catch (error) {
      setMessage(`회원가입 실패: ${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  const onLogin = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setMessage('');
    try {
      const res = await loginEmail(loginForm);
      saveLogin(res.data);
      setMessage('로그인 성공. /characters 로 이동합니다.');
      navigate('/characters');
    } catch (error) {
      setMessage(`로그인 실패: ${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="grid two-col">
      <section className="card">
        <h2>회원가입</h2>
        <form onSubmit={onRegister} className="form">
          <label>
            이름
            <input
              value={registerForm.name}
              onChange={(e) =>
                setRegisterForm((prev) => ({ ...prev, name: e.target.value }))
              }
              required
            />
          </label>
          <label>
            이메일
            <input
              type="email"
              value={registerForm.email}
              onChange={(e) =>
                setRegisterForm((prev) => ({ ...prev, email: e.target.value }))
              }
              required
            />
          </label>
          <label>
            비밀번호
            <input
              type="password"
              value={registerForm.password}
              onChange={(e) =>
                setRegisterForm((prev) => ({ ...prev, password: e.target.value }))
              }
              required
            />
          </label>
          <label>
            비밀번호 확인
            <input
              type="password"
              value={registerForm.passwordConfirm}
              onChange={(e) =>
                setRegisterForm((prev) => ({ ...prev, passwordConfirm: e.target.value }))
              }
              required
            />
          </label>
          <button type="submit" disabled={loading}>
            회원가입
          </button>
        </form>
      </section>

      <section className="card">
        <h2>로그인</h2>
        <form onSubmit={onLogin} className="form">
          <label>
            이메일
            <input
              type="email"
              value={loginForm.email}
              onChange={(e) =>
                setLoginForm((prev) => ({ ...prev, email: e.target.value }))
              }
              required
            />
          </label>
          <label>
            비밀번호
            <input
              type="password"
              value={loginForm.password}
              onChange={(e) =>
                setLoginForm((prev) => ({ ...prev, password: e.target.value }))
              }
              required
            />
          </label>
          <button type="submit" disabled={loading}>
            로그인
          </button>
        </form>
      </section>

      {message && <p className="status">{message}</p>}
    </div>
  );
}
