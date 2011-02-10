package com.eucalyptus.config;

import com.eucalyptus.bootstrap.Handles;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.DatabaseServiceBuilder;
import com.eucalyptus.component.DiscoverableServiceBuilder;
import com.eucalyptus.component.id.Walrus;

@DiscoverableServiceBuilder(Walrus.class)
@Handles( { RegisterWalrusType.class, DeregisterWalrusType.class, DescribeWalrusesType.class, WalrusConfiguration.class, ModifyWalrusAttributeType.class } )
public class WalrusBuilder extends DatabaseServiceBuilder<WalrusConfiguration> {
  
  @Override
  public WalrusConfiguration newInstance( ) {
    return new WalrusConfiguration( );
  }
  
  @Override
  public WalrusConfiguration newInstance( String partition, String name, String host, Integer port ) {
    return new WalrusConfiguration( name, host, port );
  }

  @Override
  public com.eucalyptus.component.Component getComponent( ) {
    return Components.lookup( Walrus.class );
  }

  
}
