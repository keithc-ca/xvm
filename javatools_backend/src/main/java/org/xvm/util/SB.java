package org.xvm.util;

/** Tight/tiny StringBuilder wrapper.
 *  Short short names on purpose; so they don't obscure the printing.
 *  Can't believe this wasn't done long, long ago. */
public final class SB {
  private final StringBuilder _sb;
  private int _indent = 0;
  public SB(        ) { _sb = new StringBuilder( ); }
  public SB(String s) { _sb = new StringBuilder(s); }
  public SB p( String s ) { if( s!=null ) _sb.append(s); return this; }
  public SB p( float  s ) {
    if( Float.isNaN(s) )
      _sb.append( "Float.NaN");
    else if( Float.isInfinite(s) ) {
      _sb.append(s > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY");
    } else _sb.append(s);
    return this;
  }
  public SB p( double s ) {
    if( Double.isNaN(s) )
      _sb.append("Double.NaN");
    else if( Double.isInfinite(s) ) {
      _sb.append(s > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY");
    } else _sb.append(s);
    return this;
  }
  public SB p( char   s ) { _sb.append(s); return this; }
  public SB p( int    s ) { _sb.append(s); return this; }
  public SB p( long   s ) { _sb.append(s); return this; }
  public SB p( boolean s) { _sb.append(s); return this; }
  // Not spelled "p" on purpose: too easy to accidentally say "p(1.0)" and
  // suddenly call the autoboxed version.
  public SB pobj( Object s ) { _sb.append(s.toString()); return this; }
  public SB i( int d ) { for( int i=0; i<d+_indent; i++ ) p(" "); return this; }
  public SB i( ) { return i(0); }
  public SB ip(String s) { return i().p(s); }
  public SB s() { _sb.append(' '); return this; }
  public int indent() { return _indent; }

  // Increase indentation
  public SB ii( int i) { _indent += i; return this; }
  public SB ii() { return ii(2); }
  // Decrease indentation
  public SB di( int i) { _indent -= i; return this; }
  public SB di() { return di(2); }

  public SB nl( ) { return p(System.lineSeparator()); }
  // Last printed a nl
  public boolean was_nl() {
    int len = _sb.length();
    String nl = System.lineSeparator();
    int nlen = nl.length();
    if( len < nlen ) return false;
    for( int i=0; i<nlen; i++ )
      if( _sb.charAt(len-nlen+i)!=nl.charAt(i) )
        return false;
    return true;
  }

  // Delete last char.  Useful when doing string-joins and JSON printing and an
  // extra separater char needs to be removed:
  //
  //   sb.p('[');
  //   for( Foo foo : foos )
  //     sb.p(foo).p(',');
  //   sb.unchar().p(']');  // remove extra trailing comma
  //
  public SB unchar() { return unchar(1); }
  public SB unchar(int x) { _sb.setLength(_sb.length()-x); return this; }

  public SB clear() { _sb.setLength(0); return this; }
  public int len() { return _sb.length(); }
  @Override public String toString() { return _sb.toString(); }

  // Escape an XTC string to be a compilable Java string
  public SB quote( String s ) {
    p('"');
    int len = s.length();
    for( int i=0; i<len; i++ ) {
      switch( s.charAt(i) ) {
      case '\\' -> p("\\");
      case '\n' -> p("\\n");
      case '"'  -> p("\\\"");
      default   -> p(s.charAt(i));
      }
    }
    p('"');
    return this;
  }

  // Replace all %0 with a
  public SB fmt( String fmt, String a ) {
    assert fmt.contains( "%0" ) : "Looks like the extra string is never used in the format";
    return p(fmt.replace("%0",a));
  }
  public SB ifmt( String fmt, String a ) { return i().fmt(fmt,a); }
  public SB ifmt( String fmt, long l   ) { return i().fmt(fmt,l); }
  public SB  fmt( String fmt, long l   ) { return     fmt(fmt,Long.toString(l)); }
  public SB  fmt( String fmt, String a, String b ) { return p(fmt.replace("%0",a).replace("%1",b)); }
  public SB  fmt( String fmt, String a, String b, String c ) { return p(fmt.replace("%0",a).replace("%1",b).replace("%2",c)); }
  public SB  fmt( String fmt, String a, long l ) { return     fmt(fmt,a,Long.toString(l)); }
  public SB ifmt( String fmt, String a, String b ) { return i().p(fmt.replace("%0",a).replace("%1",b)); }
  public SB ifmt( String fmt, String a, long l ) { return i().fmt(fmt,a,Long.toString(l)); }
  public SB ifmt( String fmt, long l, String a, String b ) { return i().fmt(fmt,Long.toString(l),a,b); }

}
