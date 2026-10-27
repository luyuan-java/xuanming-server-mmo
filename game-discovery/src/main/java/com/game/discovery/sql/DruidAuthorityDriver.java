package com.game.discovery.sql;

import com.game.common.sql.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/** Druid真实Driver接缝；返回受管physical，顶层DruidPooledConnection和原filter/holder不变。 */
public final class DruidAuthorityDriver implements Driver {
    private final Driver delegate;
    private final WriterAuthority authority;
    private final boolean requireTiDb;
    private final List<String> initialization;
    public DruidAuthorityDriver(Driver delegate,WriterAuthority authority,boolean requireTiDb,Collection<String> initialization)throws SQLException{
        this.delegate=Objects.requireNonNull(delegate);this.authority=Objects.requireNonNull(authority);
        this.requireTiDb=requireTiDb;this.initialization=AuthorityDataSource.checkedInitialization(initialization);
    }
    @Override public Connection connect(String url,Properties properties)throws SQLException{
        if(!delegate.acceptsURL(url))return null;
        AuthorityDataSource.validateUrl(url);
        AuthorityDataSource.validateProperties(properties);
        Connection physical=delegate.connect(url,properties);
        if(physical==null)throw new SQLException("实际driver未创建连接");
        try{
            AuthorityDataSource.initialize(physical,requireTiDb,initialization);
            StrongTiDbConnectionPolicy.verify(physical,requireTiDb);
            return AuthorityConnection.wrap(physical,authority);
        }catch(SQLException|RuntimeException|Error failure){try{physical.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
    @Override public boolean acceptsURL(String url)throws SQLException{return delegate.acceptsURL(url);}
    @Override public DriverPropertyInfo[] getPropertyInfo(String url,Properties properties)throws SQLException{return delegate.getPropertyInfo(url,properties);}
    @Override public int getMajorVersion(){return delegate.getMajorVersion();}
    @Override public int getMinorVersion(){return delegate.getMinorVersion();}
    @Override public boolean jdbcCompliant(){return delegate.jdbcCompliant();}
    @Override public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
}

